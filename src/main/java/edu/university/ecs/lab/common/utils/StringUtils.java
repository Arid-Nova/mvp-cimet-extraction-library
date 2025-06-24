package edu.university.ecs.lab.common.utils;

/**
 * This class provides various String utilities.
 */
public class StringUtils {

    /**
     * Prevent instantiation
     */
    public StringUtils() {

    }

    /**
     * Counts the number of occurrences of a substring in a string.
     * @param str The string
     * @param sub The substring to search for in str
     * @return The count of occurrences of sub in str
     */
    public static Integer countOccurrences(String str, String sub) {
        return str.split(sub, -1).length - 1;
    }

    /**
     * Remove start/end quotations from the given string.
     *
     *
     * @param s the string to remove quotations from
     * @return the string with quotations removed
     */
    public static String removeOuterQuotations(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Simplifies all path arguments to {?}.
     *
     * @param url the endpoint URL
     * @return the simplified endpoint URL
     */
    public static String simplifyEndpointURL(String url) {
        return url.replaceAll("\\{[^{}]*\\}", "{?}");
    }
}
