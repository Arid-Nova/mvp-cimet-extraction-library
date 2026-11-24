package edu.university.ecs.lab.javascript;

import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.parsers.*;
import edu.university.ecs.lab.common.parsers.treesitter.FileFacts;
import edu.university.ecs.lab.common.parsers.treesitter.PolyglotExtractor;
import edu.university.ecs.lab.common.utils.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PolyglotRegistryIntegrationTest {
    @TempDir Path temporary;
    private static final RepositoryConfig CONFIG = new RepositoryConfig(
            new RepositoryBranchPair("https://github.com/example/mixed.git", "main"), "fixture");
    private final Path adapter = Path.of("adapter/javascript").toAbsolutePath();

    private Microservice service() {
        return new Microservice(null, "declared-api", CONFIG.repoBranchPair().repositoryURL(), CONFIG.commitID(), Path.of("/"));
    }

    @Test void selectsAllLanguagesPerFileAndRecognizesTypeScriptAsSource() {
        ParserRegistry registry = ParserRegistry.createDefault(adapter, "node");
        Map<String, String> files = Map.of("Controller.java", "java", "server.ts", "javascript-typescript",
                "view.tsx", "javascript-typescript", "module.mts", "javascript-typescript", "module.cts", "javascript-typescript",
                "app.py", "python", "app.go", "go", "Api.cs", "csharp");
        files.forEach((file, language) -> {
            assertEquals(language, registry.findParserFor(Path.of(file).toFile()).orElseThrow().getCapability().getLanguage());
            assertTrue(FileUtils.isValidFile(file));
            assertFalse(FileUtils.isConfigurationFile(file));
        });
        assertTrue(registry.findParserFor(Path.of("README.md").toFile()).isEmpty());
    }

    @Test void nativeExpressProjectionPreservesMountsHandlersLocationsAndRemoteCallClassification() throws Exception {
        Files.writeString(temporary.resolve("server.ts"), """
                import express from 'express';
                import router from './routes';
                const app = express();
                app.use('/api', router);
                const fake={get(){}}; fake.get('/not-a-route');
                """);
        Files.writeString(temporary.resolve("routes.ts"), """
                import {Router} from 'express';
                import httpClient from 'axios';
                const router=Router();
                function read(req:any,res:any){return res.json({ok:true});}
                router.route('/users').get(read).post(read);
                httpClient.get('/remote');
                fetch('/create', {method:'POST'});
                export default router;
                """);
        ParserRegistry registry = ParserRegistry.createDefault(adapter, "node");
        registry.prepareRepository(temporary, CONFIG);
        Microservice service = service();
        for (String file : List.of("server.ts", "routes.ts")) {
            ParseResult result = registry.findParserFor(temporary.resolve(file).toFile()).orElseThrow()
                    .parse(service, temporary.resolve(file).toFile(), CONFIG, false);
            assertTrue(result.isSuccess(), result.getFailureReason());
            result.getClasses().forEach(service::addAbstractClass);
        }
        Set<String> endpoints = service.getEndpoints().stream().map(e -> e.getHttpMethod()+" "+e.getUrl()).collect(Collectors.toSet());
        assertEquals(Set.of("GET /api/users", "POST /api/users"), endpoints);
        Endpoint endpoint = service.getEndpoints().iterator().next();
        assertTrue(endpoint.getMetadata().getString("handler").contains("read"));
        assertEquals(5, endpoint.getMetadata().getInt("line"));
        assertEquals(ClassRole.CONTROLLER, ((AbstractClass)endpoint.getParent().orElseThrow()).getClassRole());
        assertEquals("/routes.ts", ((AbstractClass)endpoint.getParent().orElseThrow()).getMetadata().getString("sourcePath"));
        assertEquals(CONFIG.repoBranchPair().repositoryURL(), ((AbstractClass)endpoint.getParent().orElseThrow()).getMetadata().getString("repositoryURL"));
        assertEquals("declared-api", ((AbstractClass)endpoint.getParent().orElseThrow()).getMetadata().getString("componentName"));
        List<RestCall> calls = service.getClasses().stream().flatMap(c -> c.getMethods().stream())
                .flatMap(m -> m.getMethodCalls().stream()).filter(RestCall.class::isInstance).map(RestCall.class::cast).toList();
        assertEquals(2, calls.size());
        assertTrue(calls.stream().anyMatch(call -> call.getUrl().equals("/create") && call.getHttpMethod() == HttpMethod.POST));
        assertTrue(service.getClasses().stream().allMatch(c -> c.getMetadata().has("architectureEvidence")));
    }

    @Test void oneRepositoryAnalysisIsCachedUntilTheNextPreparedCheckout() throws Exception {
        Path trusted = Files.createDirectories(temporary.resolve("trusted"));
        Path root = Files.createDirectories(temporary.resolve("source"));
        Files.writeString(root.resolve("a.js"), "const a=1;");
        Files.writeString(root.resolve("b.js"), "const b=2;");
        Path count = trusted.resolve("runs.txt");
        String analyzer = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(adapter.resolve("analyzer.cjs").toString());
        String counter = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(count.toString());
        Files.writeString(trusted.resolve("analyzer.cjs"), "const fs=require('node:fs');const {analyze}=require("+analyzer+");"
                +"fs.appendFileSync("+counter+",'1');const arg=k=>process.argv[process.argv.indexOf(k)+1];"
                +"process.stdout.write(JSON.stringify(analyze({root:arg('--root'),kind:arg('--kind'),name:arg('--name'),config:{wrappers:[],backendEnv:[],backendOrigins:[]}})));" );
        JavaScriptLanguageParser parser = new JavaScriptLanguageParser(trusted, "node");
        parser.prepareRepository(root, CONFIG);
        assertTrue(parser.parse(service(), root.resolve("a.js").toFile(), CONFIG, false).isSuccess());
        assertTrue(parser.parse(service(), root.resolve("b.js").toFile(), CONFIG, false).isSuccess());
        assertEquals("1", Files.readString(count));
        parser.prepareRepository(root, CONFIG);
        assertTrue(parser.parse(service(), root.resolve("a.js").toFile(), CONFIG, false).isSuccess());
        assertEquals("11", Files.readString(count));
    }

    @Test void invalidSyntaxIsAReportedFailureAndDeclarationsAreExcluded() throws Exception {
        Files.writeString(temporary.resolve("bad.ts"), "function { broken");
        Files.writeString(temporary.resolve("types.d.ts"), "declare const a:string;");
        JavaScriptLanguageParser parser = new JavaScriptLanguageParser(adapter, "node");
        parser.prepareRepository(temporary, CONFIG);
        ParseResult bad = parser.parse(service(), temporary.resolve("bad.ts").toFile(), CONFIG, false);
        assertFalse(bad.isSuccess());
        assertTrue(bad.getFailureReason().contains("PARSE_"));
        ParseResult declaration = parser.parse(service(), temporary.resolve("types.d.ts").toFile(), CONFIG, false);
        assertTrue(declaration.isSuccess());
        assertTrue(declaration.getClasses().isEmpty());
    }

    @Test void explicitTrustedProfilesPropagateWithoutChangingGlobalConfiguration() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("source"));
        Path client = root.resolve("client.ts");
        Files.writeString(client, "import api from './apiClient'; export function read(){return api.get_request('/users');}");
        Files.writeString(root.resolve("apiClient.ts"), "export default {get_request(path:string){ return path; }};");
        Path profile = temporary.resolve("trusted-profile.json");
        Files.writeString(profile, "{\"wrappers\":[{\"module\":\"apiClient\",\"kind\":\"server\"}],\"backendEnv\":[],\"backendOrigins\":[]}");
        String globalBefore = System.getProperty("cimet.javascript.config");
        ParserRegistry registry = ParserRegistry.createDefault(adapter, "node", profile);
        registry.prepareRepository(root, CONFIG);
        ParseResult result = registry.findParserFor(client.toFile()).orElseThrow().parse(service(), client.toFile(), CONFIG, false);
        assertTrue(result.isSuccess(), result.getFailureReason());
        List<RestCall> calls = result.getClasses().stream().flatMap(module->module.getRestCalls().stream()).toList();
        assertEquals(1, calls.size());
        assertEquals("/users", calls.getFirst().getUrl());
        assertEquals("backend", calls.getFirst().getMetadata().getString("target"));
        assertEquals(globalBefore, System.getProperty("cimet.javascript.config"));
        // A separate explicit neutral profile stays independent of the configured registry.
        Path neutral = temporary.resolve("neutral-profile.json");
        Files.writeString(neutral, "{\"wrappers\":[],\"backendEnv\":[],\"backendOrigins\":[]}");
        JavaScriptLanguageParser other = new JavaScriptLanguageParser(adapter, "node", neutral);
        other.prepareRepository(root, CONFIG);
        ParseResult isolated = other.parse(service(), client.toFile(), CONFIG, false);
        assertTrue(isolated.isSuccess(), isolated.getFailureReason());
        assertEquals(0, isolated.getClasses().stream().mapToInt(module->module.getRestCalls().size()).sum());
    }

    @Test void multipleTypesUsePortableIdentitiesAndSharedPhysicalSourceMetadataWithoutSideEffects() {
        FileFacts facts = new FileFacts("/api.cs").put("types", List.of("class AlphaController", "class BetaService"))
                .put("classRole", List.of("AlphaController:CONTROLLER", "BetaService:SERVICE"))
                .put("functions", List.of("AlphaController.read", "BetaService.find"))
                .put("endpoints", "GET /users -> AlphaController.read");
        Microservice service = service();
        List<AbstractClass> classes = PolyglotFactsToIrConverter.convertAll(service, Path.of("/api.cs"), "api", facts, false);
        assertEquals(2, classes.size());
        assertTrue(service.getClasses().isEmpty());
        assertEquals(2, classes.stream().map(AbstractClass::getID).distinct().count());
        assertTrue(classes.stream().allMatch(c -> !c.getPath().toString().contains(":")));
        assertTrue(classes.stream().allMatch(c -> "/api.cs".equals(c.getMetadata().getString("sourcePath"))));
    }

    @Test void loosePythonEndpointsAreVisibleThroughNativeServiceAccessors() throws Exception {
        Path source = temporary.resolve("app.py");
        Files.writeString(source, "from flask import Flask\napp=Flask(__name__)\n@app.route('/users')\ndef users():\n    return []\n");
        try (var ignored = FileUtils.withRepositoryRoot(CONFIG.getRepoName(), temporary)) {
            ParseResult parsed = new PolyglotLanguageParser(PolyglotExtractor.Language.PYTHON).parse(service(), source.toFile(), CONFIG, false);
            assertTrue(parsed.isSuccess(), parsed.getFailureReason());
            Microservice service = service();
            parsed.getClasses().forEach(service::addAbstractClass);
            assertEquals(1, service.getEndpoints().size());
            assertEquals("/app.py", parsed.getAbstractClass().getMetadata().getString("sourcePath"));
        }
    }

    @Test void localRootScopeIsNestedAndRestoresLegacyPathBehavior() {
        Path root = temporary.resolve("outer");
        try (var ignored = FileUtils.withRepositoryRoot("mixed", root)) {
            assertEquals("/a.ts", FileUtils.localPathToGitPath(root.resolve("a.ts").toString(), "mixed"));
            Path inner = temporary.resolve("inner");
            try (var nested = FileUtils.withRepositoryRoot("mixed", inner)) {
                assertEquals("/b.py", FileUtils.localPathToGitPath(inner.resolve("b.py").toString(), "mixed"));
            }
            assertEquals("/a.ts", FileUtils.localPathToGitPath(root.resolve("a.ts").toString(), "mixed"));
        }
        assertEquals("/Controller.java", FileUtils.localPathToGitPath("./clone/mixed/Controller.java", "mixed"));
    }

    @Test void oversizedJavaAndTreeSitterSourcesReturnExplicitFailuresWithoutParsing() throws Exception {
        byte[] large = new byte[edu.university.ecs.lab.common.utils.SourceFileReader.MAX_SOURCE_BYTES + 1];
        Arrays.fill(large, (byte)' ');
        Path java = temporary.resolve("Large.java"), python = temporary.resolve("large.py");
        Files.write(java, large); Files.write(python, large);
        try (var ignored = FileUtils.withRepositoryRoot(CONFIG.getRepoName(), temporary)) {
            ParseResult javaResult = new JavaLanguageParser().parse(service(), java.toFile(), CONFIG, false);
            ParseResult pythonResult = new PolyglotLanguageParser(PolyglotExtractor.Language.PYTHON).parse(service(), python.toFile(), CONFIG, false);
            for (ParseResult result : List.of(javaResult, pythonResult)) {
                assertFalse(result.isSuccess());
                assertTrue(result.getClasses().isEmpty());
                assertTrue(result.getFailureReason().contains("SOURCE_FILE_TOO_LARGE"), result.getFailureReason());
            }
        }
    }

    @Test void dependencyAndGeneratedTreesAreExcludedFromGenericAndRepositoryWideAnalysis() throws Exception {
        Files.writeString(temporary.resolve("server.js"), "const express=require('express');const app=express();app.get('/real',()=>{});");
        for (String folder : List.of(".venv", "venv", "__pycache__", "bin", "obj", "dist", "vendor", ".next", "coverage")) {
            Path generated = Files.createDirectories(temporary.resolve(folder));
            Files.writeString(generated.resolve("bad.cs"), "class { BROKEN");
            Files.writeString(generated.resolve("fake.js"), "const express=require('express');const app=express();app.get('/generated',()=>{});");
        }
        MicroserviceSystem system = edu.university.ecs.lab.intermediate.create.services.IRExtractionService.extractLocal(
                "fixture", temporary, CONFIG, "application");
        assertEquals("complete-for-supported-source-patterns", system.getMetadata().getString("status"));
        Microservice service = system.getMicroservices().iterator().next();
        assertEquals(Set.of("/real"), service.getEndpoints().stream().map(Endpoint::getUrl).collect(Collectors.toSet()));
        assertEquals(1, service.getClasses().size());
    }

    @Test void preparingJavaOnlyRegistryDoesNotRequireNodeOrAdapter() {
        ParserRegistry registry = ParserRegistry.createDefault(temporary.resolve("missing"), "nonexistent-node");
        assertDoesNotThrow(() -> registry.prepareRepository(temporary, CONFIG));
        assertInstanceOf(JavaLanguageParser.class, registry.findParserFor(Path.of("Controller.java").toFile()).orElseThrow());
    }

    @Test void csharpAttributeArgumentsPreserveLiteralsPrefixesAndAbsoluteRouteOverrides() throws Exception {
        Path source = temporary.resolve("CartController.cs");
        Files.writeString(source, """
                [Route("api/[controller]")]
                public class CartController : ControllerBase {
                  [HttpGet("items")] public string List(){return "ok";}
                  [HttpPost("/absolute")] public string Create(){return "ok";}
                  [HttpDelete("~/root")] public string Delete(){return "ok";}
                }
                """);
        try (var ignored = FileUtils.withRepositoryRoot(CONFIG.getRepoName(), temporary)) {
            ParseResult result = new PolyglotLanguageParser(PolyglotExtractor.Language.CSHARP).parse(service(), source.toFile(), CONFIG, false);
            assertTrue(result.isSuccess(), result.getFailureReason());
            Set<String> urls = result.getClasses().stream().flatMap(c->c.getMethods().stream()).filter(Endpoint.class::isInstance)
                    .map(Endpoint.class::cast).map(Endpoint::getUrl).collect(Collectors.toSet());
            assertEquals(Set.of("/api/Cart/items", "/absolute", "/root"), urls);
        }
    }

    @Test void malformedAndPackageLessJavaCannotReuseThePreviousCompilationUnit() throws Exception {
        Files.writeString(temporary.resolve("Good.java"), "package demo; public class Good { public void good(){} }");
        Files.writeString(temporary.resolve("Bad.java"), "public class Bad { String leaked='SECRET_LITERAL'; void broken( } ");
        Files.writeString(temporary.resolve("Plain.java"), "public class Plain { public void plain(){} }");
        JavaLanguageParser parser = new JavaLanguageParser();
        try (var ignored = FileUtils.withRepositoryRoot(CONFIG.getRepoName(), temporary)) {
            ParseResult good = parser.parse(service(), temporary.resolve("Good.java").toFile(), CONFIG, false);
            assertTrue(good.isSuccess(), good.getFailureReason());
            assertEquals("demo", good.getAbstractClass().getPackageName());
            ParseResult bad = parser.parse(service(), temporary.resolve("Bad.java").toFile(), CONFIG, false);
            assertFalse(bad.isSuccess());
            assertTrue(bad.getClasses().isEmpty());
            assertFalse(bad.getFailureReason().contains("SECRET_LITERAL"));
            ParseResult plain = parser.parse(service(), temporary.resolve("Plain.java").toFile(), CONFIG, false);
            assertTrue(plain.isSuccess(), plain.getFailureReason());
            assertEquals("", plain.getAbstractClass().getPackageName());
            assertEquals("Plain", plain.getAbstractClass().getName());
        }
    }

    @Test void javaSymbolResolutionUsesTheCurrentRepositoryRoot() throws Exception {
        JavaLanguageParser parser = new JavaLanguageParser();
        for (String suffix : List.of("One", "Two")) {
            Path root = Files.createDirectories(temporary.resolve("repo"+suffix));
            Path pkg = Files.createDirectories(root.resolve("demo"));
            Files.writeString(pkg.resolve("Target"+suffix+".java"), "package demo; public class Target"+suffix+" {public void work(){}}");
            Files.writeString(pkg.resolve("Provider.java"), "package demo; public class Provider {public Target"+suffix+" get(){return null;}}");
            Path caller = pkg.resolve("Uses.java");
            Files.writeString(caller, "package demo; public class Uses {public void run(){new Provider().get().work();}}");
            try (var ignored = FileUtils.withRepositoryRoot(CONFIG.getRepoName(), root)) {
                ParseResult result = parser.parse(service(), caller.toFile(), CONFIG, false);
                assertTrue(result.isSuccess(), result.getFailureReason());
                List<MethodCall> calls = result.getAbstractClass().getMethods().stream().flatMap(m->m.getMethodCalls().stream()).toList();
                MethodCall work = calls.stream().filter(call->"work".equals(call.getName())).findFirst().orElseThrow();
                assertEquals("Target"+suffix, work.getObjectType());
            }
        }
    }
}
