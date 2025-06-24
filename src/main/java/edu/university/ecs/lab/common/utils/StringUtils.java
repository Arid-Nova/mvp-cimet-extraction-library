package edu.university.ecs.lab.common.utils;

public class StringUtils {
    public static Integer countOccurrences(String str, String sub) {
        return str.split(sub, -1).length - 1;
    }
}
