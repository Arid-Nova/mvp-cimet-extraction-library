package edu.university.ecs.lab.common.models.enums;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
import edu.university.ecs.lab.common.utils.StringUtils;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Factory class for generating an endpoint template from annotations
 */
@Getter
public class EndpointTemplate {
    public static final List<String> ENDPOINT_ANNOTATIONS = Arrays.asList("RequestMapping", "GetMapping", "PutMapping", "PostMapping", "DeleteMapping", "PatchMapping");
    private final HttpMethod httpMethod;
    private final String name;
    private final String url;

    public EndpointTemplate(AnnotationExpr requestMapping, AnnotationExpr endpointMapping) {
        this(requestMapping, endpointMapping, null);
    }

    /**
     * Like {@link #EndpointTemplate(AnnotationExpr, AnnotationExpr)}, but for JAX-RS: a bare verb
     * marker ({@code @GET}, {@code @POST}, ...) carries no path of its own, unlike Spring's combined
     * {@code @GetMapping("/foo")}. {@code jaxrsSubPath} is that method's own sibling {@code @Path}
     * annotation, if any, supplying the sub-path appended after the class-level {@code @Path} prefix.
     */
    public EndpointTemplate(AnnotationExpr requestMapping, AnnotationExpr endpointMapping, AnnotationExpr jaxrsSubPath) {
        HttpMethod finalHttpMethod = HttpMethod.ALL;

        String preUrl = "";
        if(requestMapping != null) {
            if (requestMapping instanceof NormalAnnotationExpr) {
                NormalAnnotationExpr nae = (NormalAnnotationExpr) requestMapping;
                for (MemberValuePair pair : nae.getPairs()) {
                    // path and value are aliases of each other in Spring's @RequestMapping
                    if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                        preUrl = firstPathValue(pair.getValue()).replaceAll("\"", "");
                    }
                }
            } else if (requestMapping instanceof SingleMemberAnnotationExpr) {
                preUrl = firstPathValue(requestMapping.asSingleMemberAnnotationExpr().getMemberValue()).replaceAll("\"", "");
            }
        }

        String url = "";
        if (endpointMapping instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr nae = (NormalAnnotationExpr) endpointMapping;
            for (MemberValuePair pair : nae.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    // method = {RequestMethod.GET} is an array; its raw text matches no verb.
                    String methodValue = firstPathValue(pair.getValue());
                    finalHttpMethod = httpFromMapping(methodValue);
                } else if(pair.getNameAsString().equals("path") || pair.getNameAsString().equals("value")) {
                    url = firstPathValue(pair.getValue()).replaceAll("\"", "");
                }
            }
        } else if (endpointMapping instanceof SingleMemberAnnotationExpr) {
            url = firstPathValue(endpointMapping.asSingleMemberAnnotationExpr().getMemberValue()).replaceAll("\"", "");
        } else if(endpointMapping instanceof MarkerAnnotationExpr) {
            if(preUrl.isEmpty()) {
                url = "/";
            }
        }

        if (jaxrsSubPath != null) {
            String subUrl = extractPathValue(jaxrsSubPath);
            if (!subUrl.isEmpty()) {
                url = subUrl;
            }
        }

        if(finalHttpMethod == HttpMethod.ALL) {
            finalHttpMethod = httpFromMapping(endpointMapping.getNameAsString());
        }

        String finalURL = "";
        // Ensure preUrl starts with a slash if it exists
        if (!preUrl.isEmpty() && !preUrl.startsWith("/")) {
            preUrl = "/" + preUrl;
        }
        // Ensure url starts with a slash if it exists - independently of preUrl, so a non-slash-prefixed
        // sub-path (e.g. JAX-RS's common @Path("_query") convention) still joins on its own "/" instead
        // of concatenating directly onto preUrl (was only checked when preUrl was empty).
        if (!url.isEmpty() && !url.startsWith("/")) {
            url = "/" + url;
        }


        if(preUrl.isEmpty() && url.isEmpty()) {
            finalURL = "/";
        } else {
            finalURL = preUrl + url;
        }

        // Replace any double slashes
        finalURL = finalURL.replaceAll("//", "/");
        // If it ends with a slash remove it
        finalURL = finalURL.endsWith("/") && !finalURL.equals("/") ? finalURL.substring(0, finalURL.length() - 1) : finalURL;


        // Get query Parameters
        this.httpMethod = finalHttpMethod;
        this.name = endpointMapping.getNameAsString();
        this.url = simplifyEndpointURL(finalURL);
    }


    /**
     * Method to get http method from mapping
     * 
     * @param mapping mapping string for a given method
     * @return HttpMethod object of same method type
     */
    private static HttpMethod httpFromMapping(String mapping) {
        switch (mapping) {
            case "GetMapping":
            case "RequestMethod.GET":
                return HttpMethod.GET;
            case "PostMapping":
            case "RequestMethod.POST":
                return HttpMethod.POST;
            case "DeleteMapping":
            case "RequestMethod.DELETE":
                return HttpMethod.DELETE;
            case "PutMapping":
            case "RequestMethod.PUT":
                return HttpMethod.PUT;
            case "PatchMapping":
            case "RequestMethod.PATCH":
                return HttpMethod.PATCH;
            // JAX-RS: the verb is its own bare marker annotation (@GET, @POST, ...), not a string argument.
            case "GET":
                return HttpMethod.GET;
            case "POST":
                return HttpMethod.POST;
            case "PUT":
                return HttpMethod.PUT;
            case "DELETE":
                return HttpMethod.DELETE;
            case "PATCH":
                return HttpMethod.PATCH;
            case "HEAD":
                return HttpMethod.HEAD;
            case "OPTIONS":
                return HttpMethod.OPTIONS;
            default:
                return HttpMethod.ALL;
        }

    }

    /**
     * Extracts a {@code @Path}-style annotation's string value, whether declared as
     * {@code @Path("/foo")} or {@code @Path(value = "/foo")}.
     */
    private static String extractPathValue(AnnotationExpr ae) {
        if (ae.isSingleMemberAnnotationExpr()) {
            return firstPathValue(ae.asSingleMemberAnnotationExpr().getMemberValue()).replaceAll("\"", "");
        }
        if (ae.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ae.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals("value")) {
                    return firstPathValue(pair.getValue()).replaceAll("\"", "");
                }
            }
        }
        return "";
    }

    /**
     * Method to get endpoint path from annotations
     *
     * @param ae annotation expression from method
     * @param url string formatted as a url
     * @return endpoint path/url from annotation expression
     */
    public static String getPathFromAnnotation(AnnotationExpr ae, String url) {
        // Annotations of type @Mapping("/endpoint")
        if (ae.isSingleMemberAnnotationExpr()) {
            url = url + StringUtils.simplifyEndpointURL(
                    StringUtils.removeOuterQuotations(
                            firstPathValue(ae.asSingleMemberAnnotationExpr().getMemberValue())));
        }

        // Annotations of type @Mapping(path="/endpoint")
        else if (ae.isNormalAnnotationExpr() && !ae.asNormalAnnotationExpr().getPairs().isEmpty()) {
            for (MemberValuePair mvp : ae.asNormalAnnotationExpr().getPairs()) {
                if (mvp.getName().toString().equals("path") || mvp.getName().toString().equals("value")) {
                    url = url + StringUtils.simplifyEndpointURL(
                            StringUtils.removeOuterQuotations(firstPathValue(mvp.getValue())));
                    break;
                }
            }
        }
        return url;
    }

    /**
     * A mapping annotation listing several paths ({@code @GetMapping({"/foo", "/{workspace}/foo"})},
     * e.g. geoserver-cloud's KMLReflectorController) declares one route per path. Returns one copy of
     * the annotation per path, each with just that single path, or the annotation itself when it has at
     * most one.
     */
    public static List<AnnotationExpr> splitPaths(AnnotationExpr mapping) {
        Expression paths = null;
        if (mapping instanceof SingleMemberAnnotationExpr single) {
            paths = single.getMemberValue();
        } else if (mapping instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    paths = pair.getValue();
                }
            }
        }
        if (paths == null || !paths.isArrayInitializerExpr() || paths.asArrayInitializerExpr().getValues().size() <= 1) {
            return List.of(mapping);
        }

        List<AnnotationExpr> perPath = new ArrayList<>();
        for (Expression path : paths.asArrayInitializerExpr().getValues()) {
            AnnotationExpr copy = mapping.clone();
            if (copy instanceof SingleMemberAnnotationExpr single) {
                single.setMemberValue(path.clone());
            } else {
                for (MemberValuePair pair : copy.asNormalAnnotationExpr().getPairs()) {
                    if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                        pair.setValue(path.clone());
                    }
                }
            }
            perPath.add(copy);
        }
        return perPath;
    }

    /**
     * A path member can be an array of several paths; callers wanting every route use
     * {@link #splitPaths} first, so anything reaching here with an array (e.g. a class-level
     * @RequestMapping prefix) uses its first path rather than JavaParser's raw array-initializer text,
     * which would corrupt the URL once {@link #simplifyEndpointURL} runs its placeholder regex over it.
     */
    private static String firstPathValue(Expression value) {
        if (value.isArrayInitializerExpr()) {
            NodeList<Expression> values = value.asArrayInitializerExpr().getValues();
            return values.isEmpty() ? "" : values.get(0).toString();
        }
        return value.toString();
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
