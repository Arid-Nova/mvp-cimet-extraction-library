package edu.university.ecs.lab.javascript;

import edu.university.ecs.lab.common.config.*;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.delta.services.*;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Full clone/extract/revision-delta/merge path using local Git objects and a child-only URL rewrite. */
class PolyglotRemoteFixtureTest {
    @TempDir Path temporary;
    private void write(Path root,String path,String text) throws Exception {
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
    private String endpoint(String path) {
        return "const express=require('express'); const app=express(); app.get('"+path+"',(req,res)=>res.json({ok:true}));";
    }
    private String commit(Git git,String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor("Fixture","fixture@example.invalid")
                .setCommitter("Fixture","fixture@example.invalid").call().getName();
    }
    @Test void realGitMultiRepositorySnapshotDeltaAndMergeUseAllParsers() throws Exception {
        Path first=temporary.resolve("first"),second=temporary.resolve("second"),run=temporary.resolve("run");
        Files.createDirectories(run);
        String oldRevision,newRevision,secondRevision;
        try(Git git=Git.init().setDirectory(first.toFile()).setInitialBranch("main").call()) {
            write(first,"package.json","{\"name\":\"first\",\"main\":\"server.ts\"}");
            write(first,"server.ts",endpoint("/before"));
            write(first,"HelloController.java","import org.springframework.web.bind.annotation.*; @RestController public class HelloController { @GetMapping(\"/java\") public String get(){return \"ok\";} }");
            oldRevision=commit(git,"before");write(first,"server.ts",endpoint("/after"));newRevision=commit(git,"after");
        }
        try(Git git=Git.init().setDirectory(second.toFile()).setInitialBranch("main").call()) {
            write(second,"requirements.txt","# No dependencies are installed by extraction\n");
            write(second,"app.py","@app.get('/python')\ndef users():\n    return []\n");secondRevision=commit(git,"python");
        }
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        ProcessBuilder command=new ProcessBuilder(java,"-cp",classpath,Worker.class.getName(),oldRevision,newRevision,secondRevision,
                Path.of("adapter/javascript").toAbsolutePath().toString());
        command.directory(run.toFile());
        Map<String,String> env=command.environment();
        env.put("CONFIG_SERVER_URL","http://127.0.0.1:9");env.put("INTERNAL_SERVICE_KEY","");
        env.put("GIT_CONFIG_COUNT","2");
        env.put("GIT_CONFIG_KEY_0","url."+first.toUri()+".insteadOf");env.put("GIT_CONFIG_VALUE_0","https://github.com/fixture/first.git");
        env.put("GIT_CONFIG_KEY_1","url."+second.toUri()+".insteadOf");env.put("GIT_CONFIG_VALUE_1","https://github.com/fixture/second.git");
        Path log=run.resolve("fixture.log");command.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process=command.start();
        try {
            assertTrue(process.waitFor(90,TimeUnit.SECONDS),"Local fixture extraction timed out");
            assertEquals(0,process.exitValue(),Files.readString(log));
            assertTrue(Files.readString(run.resolve("verified.txt")).contains("three-languages-two-repositories"));
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    public static class Worker {
        public static void main(String[] args) throws Exception {
            System.setProperty("cimet.javascript.adapter",args[3]);
            var first=new RepositoryConfig(new RepositoryBranchPair("https://github.com/fixture/first.git","main"),args[0]);
            var second=new RepositoryConfig(new RepositoryBranchPair("https://github.com/fixture/second.git","main"),args[2]);
            var config=new Config("local-fixture",List.of(first,second));
            MicroserviceSystem before=IRExtractionService.create(config);
            assertEquals(2,before.getMicroservices().size());
            Set<String> urls=new HashSet<>();before.getMicroservices().forEach(s->s.getEndpoints().forEach(e->urls.add(e.getUrl())));
            assertTrue(urls.containsAll(Set.of("/before","/java","/python")),urls.toString());
            var delta=DeltaExtractionService.create(config,before,Map.of(first,args[1]));
            assertEquals(2,delta.getOldCommits().size());assertEquals(2,delta.getNewCommits().size());
            assertFalse(delta.getChanges().isEmpty());
            var targetConfig=new Config("local-fixture",List.of(new RepositoryConfig(first.repoBranchPair(),args[1]),second));
            MicroserviceSystem target=IRExtractionService.create(targetConfig);
            MicroserviceSystem merged=MergeService.create(SnapshotDeltaService.copy(before),delta);
            assertEquals(SnapshotDeltaService.fingerprint(target),SnapshotDeltaService.fingerprint(merged));
            Files.writeString(Path.of("verified.txt"),"three-languages-two-repositories");
        }
    }
}
