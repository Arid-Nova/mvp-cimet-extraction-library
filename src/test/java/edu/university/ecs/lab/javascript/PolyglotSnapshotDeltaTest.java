package edu.university.ecs.lab.javascript;

import edu.university.ecs.lab.common.config.*;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.parsers.*;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.*;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import edu.university.ecs.lab.delta.services.SnapshotDeltaService;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class PolyglotSnapshotDeltaTest {
    @TempDir Path temporary;

    private RepositoryConfig repository(String name, char revision) {
        return new RepositoryConfig(new RepositoryBranchPair("https://github.com/fixture/" + name + ".git", "main"),
                String.valueOf(revision).repeat(40));
    }
    private void write(Path root, String name, String body) throws Exception {
        Path target = root.resolve(name); Files.createDirectories(target.getParent()); Files.writeString(target, body);
    }
    private String express(String route) {
        return "const express=require('express'); const app=express(); function list(req,res){res.json({ok:true});} app.get('"+route+"',list);";
    }
    private MicroserviceSystem extract(Path root, char revision) throws Exception {
        return IRExtractionService.extractLocal("mixed",root,repository("mixed",revision),"application");
    }
    @Test void oneRepositoryContainsFiveLanguagesAndTypeScriptWithoutExecutingItsCode() throws Exception {
        Path root=temporary.resolve("source");
        write(root,"src/HelloController.java", "package demo; import org.springframework.web.bind.annotation.*; @RestController public class HelloController { @GetMapping(\"/java\") public String hello(){return \"ok\";} }");
        write(root,"server.ts", express("/typescript") + " throw new Error('TARGET MUST NOT EXECUTE');");
        write(root,"routes.py", "@app.get('/python')\ndef users():\n    return []\n");
        write(root,"main.go", "package main\nfunc routes(){ router.GET(\"/go\", handler) }\n");
        write(root,"ItemsController.cs", "[ApiController] public class ItemsController : ControllerBase { [HttpGet(\"/csharp\")] public string List(){return \"ok\";} }");
        MicroserviceSystem system=extract(root,'a');
        assertEquals(1,system.getMicroservices().size());
        Set<String> urls=system.getMicroservices().iterator().next().getEndpoints().stream().map(Endpoint::getUrl).collect(Collectors.toSet());
        assertTrue(urls.containsAll(Set.of("/java","/typescript","/python","/go","/csharp")),urls.toString());
        assertFalse(system.getMetadata().toString().contains("SOURCE_PARSE_FAILED"),system.getMetadata().toString());
        Path json=temporary.resolve("roundtrip.json"); JsonReadWriteUtils.writeToJSON(json,system);
        assertEquals(SnapshotDeltaService.fingerprint(system),SnapshotDeltaService.fingerprint(IRExtractionService.read(json)));
    }
    @Test void additionsModificationsDeletionsAndRenamesMergeExactlyWithoutMutatingBase() throws Exception {
        Path root=temporary.resolve("source");write(root,"server.ts",express("/before"));
        write(root,"removed.py","@app.get('/old')\ndef old():\n    return []\n");
        write(root,"rename.go","package main\nfunc routes(){router.GET(\"/go\", handler)}\n");
        MicroserviceSystem before=extract(root,'a');String original=SnapshotDeltaService.fingerprint(before);
        write(root,"server.ts",express("/after")); Files.delete(root.resolve("removed.py"));
        Files.move(root.resolve("rename.go"),root.resolve("renamed.go"));
        write(root,"new.cs","public class CartService { public void NewMethod(){} }");
        MicroserviceSystem after=extract(root,'b');SystemChange delta=SnapshotDeltaService.compare(before,after);
        assertTrue(delta.getChanges().stream().anyMatch(c->c.getChangeType()==ChangeType.MODIFY&&c.getPath().toString().endsWith("server.ts")));
        assertTrue(delta.getChanges().stream().anyMatch(c->c.getChangeType()==ChangeType.DELETE&&c.getPath().toString().endsWith("removed.py")));
        assertTrue(delta.getChanges().stream().anyMatch(c->c.getChangeType()==ChangeType.DELETE&&c.getPath().toString().endsWith("rename.go")));
        assertTrue(delta.getChanges().stream().anyMatch(c->c.getChangeType()==ChangeType.ADD&&c.getPath().toString().endsWith("renamed.go")));
        Path json=temporary.resolve("delta.json");JsonReadWriteUtils.writeToJSON(json,delta);
        SystemChange saved=JsonReadWriteUtils.readFromJSON(json,SystemChange.class);
        MicroserviceSystem merged=MergeService.create(SnapshotDeltaService.copy(before),saved);
        assertEquals(SnapshotDeltaService.fingerprint(after),SnapshotDeltaService.fingerprint(merged));
        assertEquals(original,SnapshotDeltaService.fingerprint(before),"Comparison must not clear base descendants");
        assertThrows(IllegalArgumentException.class,()->MergeService.create(after,saved),"Wrong bases must be rejected");
    }
    @Test void repositoryIdentitiesDisambiguateSamePathsAndBoundaryChangesSurviveMerge() throws Exception {
        Path first=temporary.resolve("first"),second=temporary.resolve("second");
        write(first,"server.js",express("/one"));write(second,"server.js",express("/two"));
        var inputs=List.of(new IRExtractionService.LocalSource(first,repository("first",'a'),"first"),new IRExtractionService.LocalSource(second,repository("second",'a'),"second"));
        MicroserviceSystem before=IRExtractionService.extractLocal("mixed",inputs);
        write(second,"server.js",express("/changed"));
        var targets=List.of(new IRExtractionService.LocalSource(first,repository("first",'a'),"first"),new IRExtractionService.LocalSource(second,repository("second",'b'),"renamed-application"));
        MicroserviceSystem after=IRExtractionService.extractLocal("mixed",targets);
        SystemChange delta=SnapshotDeltaService.compare(before,after);
        assertFalse(delta.getChanges().isEmpty());
        assertTrue(delta.getChanges().stream().allMatch(c->c.getRepositoryURL().endsWith("/second.git")));
        assertEquals(2,delta.getNewCommits().size());
        assertEquals(SnapshotDeltaService.fingerprint(after),SnapshotDeltaService.fingerprint(MergeService.create(before,delta)));
    }
    @Test void customLanguageCanBeRegisteredWithoutEditingDiscoveryOrExtraction() throws Exception {
        Path root=temporary.resolve("custom");write(root,"hello.newlang","declaration");
        ParserRegistry registry=new ParserRegistry();
        registry.register(new LanguageParser(){
            public boolean canParse(File file){return file.getName().endsWith(".newlang");}
            public ParserCapability getCapability(){return new ParserCapability("newlang",Set.of(".newlang"),Set.of("fixture grammar"));}
            public ParseResult parse(Microservice service,File file,RepositoryConfig config,boolean filter){
                JClass cls=new JClass(service,Path.of("/hello.newlang"),null,"",Set.of());
                cls.setClassRole(edu.university.ecs.lab.common.models.enums.ClassRole.SERVICE);return ParseResult.success(cls);
            }
        });
        MicroserviceSystem result=IRExtractionService.extractLocal("custom",List.of(new IRExtractionService.LocalSource(root,repository("custom",'a'),"custom")),registry);
        assertEquals(1,result.getMicroservices().iterator().next().getServices().size());
        assertTrue(result.getMetadata().getJSONArray("languages").toList().contains("newlang"));
    }
    @Test void malformedTargetCannotBeMisreportedAsDeletedSource() throws Exception {
        Path root=temporary.resolve("source");write(root,"server.ts",express("/before"));
        MicroserviceSystem before=extract(root,'a');
        write(root,"server.ts","const incomplete = ;");
        MicroserviceSystem incomplete=extract(root,'b');
        assertEquals("partial",incomplete.getMetadata().getString("status"));
        assertThrows(IllegalArgumentException.class,()->SnapshotDeltaService.compare(before,incomplete));
    }
    @Test void ambiguousLocalRepositoryRootsAreRejected() throws Exception {
        Path first=temporary.resolve("first"),second=temporary.resolve("second");
        write(first,"server.ts",express("/one"));write(second,"server.ts",express("/two"));
        var inputs=List.of(new IRExtractionService.LocalSource(first,repository("same",'a'),"one"),new IRExtractionService.LocalSource(second,repository("same",'a'),"two"));
        assertThrows(IllegalArgumentException.class,()->IRExtractionService.extractLocal("mixed",inputs));
    }

}
