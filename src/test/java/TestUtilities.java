import edu.university.ecs.lab.common.models.ir.*;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.util.*;

public class TestUtilities {
    public static final String RESOURCES_PATH = "src" + File.separator + "test" + File.separator + "resources";
    public static final String CONFIGS_PATH = RESOURCES_PATH + File.separator + "configs";
    public static final String JAVA_FILES_PATH = RESOURCES_PATH + File.separator + "java_files";

    public static void deepCompareSystems(MicroserviceSystem microserviceSystem1, MicroserviceSystem microserviceSystem2) {
        System.out.println("System equivalence is: " + Objects.deepEquals(microserviceSystem1, microserviceSystem2));

        for (Microservice microservice1 : microserviceSystem1.getMicroservices()) {
            outer2: {
                for (Microservice microservice2 : microserviceSystem2.getMicroservices()) {
                    if (microservice1.getName().equals(microservice2.getName())) {
                        System.out.println("Microservice equivalence of " + microservice1.getPath() + " is: " + Objects.deepEquals(microservice1, microservice2));
                        if (!Objects.deepEquals(microservice1, microservice2)) {
                            System.out.println("Differences in Microservice " + microservice1.getName() + ":");
                            if (!Objects.equals(microservice1.getName(), microservice2.getName())) {
                                System.out.println("  name: " + microservice1.getName() + " vs " + microservice2.getName());
                            }
                            if (!Objects.equals(microservice1.getRepositoryURL(), microservice2.getRepositoryURL())) {
                                System.out.println("  repositoryURL: " + microservice1.getRepositoryURL() + " vs " + microservice2.getRepositoryURL());
                            }
                            if (!Objects.equals(microservice1.getCommitID(), microservice2.getCommitID())) {
                                System.out.println("  commitID: " + microservice1.getCommitID() + " vs " + microservice2.getCommitID());
                            }
                            if (!Objects.equals(microservice1.getPath(), microservice2.getPath())) {
                                System.out.println("  path: " + microservice1.getPath() + " vs " + microservice2.getPath());
                            }
                            if (!Objects.equals(microservice1.getControllers(), microservice2.getControllers())) {
                                System.out.println("  controllers: unequal");
                            }
                            if (!Objects.equals(microservice1.getServices(), microservice2.getServices())) {
                                System.out.println("  services: unequal");
                            }
                            if (!Objects.equals(microservice1.getRepositories(), microservice2.getRepositories())) {
                                System.out.println("  repositories: unequal");
                            }
                            if (!Objects.equals(microservice1.getEntities(), microservice2.getEntities())) {
                                System.out.println("  entities: unequal");
                            }
                            if (!Objects.equals(microservice1.getUnknowns(), microservice2.getUnknowns())) {
                                System.out.println("  unknowns: unequal");
                                System.out.println("    1: " + microservice1.getUnknowns());
                                System.out.println("    2: " + microservice2.getUnknowns());
                            }
                            if (!Objects.equals(microservice1.getFeignClients(), microservice2.getFeignClients())) {
                                System.out.println("  feignClients: unequal");
                            }
                            if (!Objects.equals(microservice1.getFiles(), microservice2.getFiles())) {
                                System.out.println("  files: unequal");
                                System.out.println("    1: " + microservice1.getFiles());
                                System.out.println("    2: " + microservice2.getFiles());
                            }
                            if (!Objects.equals(microservice1.getMetadata(), microservice2.getMetadata())) {
                                System.out.println("  metadata: unequal (1: " + microservice1.getMetadata() + ", 2: " + microservice2.getMetadata() + ")");
                            }
                        }
                        for (ProjectFile projectFile1 : microservice1.getAllFiles()) {
                            outer1: {
                                for (ProjectFile projectFile2 : microservice2.getAllFiles()) {
                                    if (projectFile1.getPath().equals(projectFile2.getPath())) {
                                        System.out.println("Class equivalence of " + projectFile1.getPath() + " is: " + Objects.deepEquals(projectFile1, projectFile2));
                                        if (!Objects.deepEquals(projectFile1, projectFile2)) {
                                            Map<String, Set<MethodCall>> diffs = findSetDifferences(new HashSet<>(((JClass)projectFile1).getMethodCalls()), new HashSet<>(((JClass)projectFile2).getMethodCalls()));
                                            System.out.println("Class differences: ");
                                            for(Map.Entry<String, Set<MethodCall>> entry : diffs.entrySet()) {
                                                System.out.println(entry.getKey());
                                                entry.getValue().forEach(mc -> System.out.println(mc.getName()));
                                            }
                                        }
                                        break outer1;
                                    }
                                }
                                System.out.println("No JClass match found for " + projectFile1.getPath());
                            }
                        }
                        break outer2;
                    }
                }
                System.out.println("No Microservice match found for " + microservice1.getPath());
            }
        }
    }

    public static List<RevCommit> iterableToList(Iterable<RevCommit> iterable) {
        Iterator<RevCommit> iterator = iterable.iterator();
        List<RevCommit> list = new LinkedList<>();
        while (iterator.hasNext()) {
            list.add(iterator.next());
        }
        Collections.reverse(list);

        return list;
    }

    public static <T> Map<String, Set<T>> findSetDifferences(Set<T> set1, Set<T> set2) {
        Set<T> inFirstNotInSecond = new HashSet<>(set1);
        inFirstNotInSecond.removeAll(set2); // Elements in set1 but not in set2

        Set<T> inSecondNotInFirst = new HashSet<>(set2);
        inSecondNotInFirst.removeAll(set1); // Elements in set2 but not in set1

        Map<String, Set<T>> result = new HashMap<>();
        result.put("InFirstNotInSecond", inFirstNotInSecond);
        result.put("InSecondNotInFirst", inSecondNotInFirst);

        return result;
    }
}
