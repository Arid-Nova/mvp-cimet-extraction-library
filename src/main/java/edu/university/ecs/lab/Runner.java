package edu.university.ecs.lab;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;

import java.nio.file.Path;

public class Runner {
    public static void main(String[] args) throws Exception {
        Config config = ConfigUtil.readConfigFromFile(Path.of("config.json"));
        IRExtractionService.createAndWrite(config, "output-ir.json");
        System.out.println("Done. Check output-ir.json");
    }
}