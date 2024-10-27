package net.microstar.dispatcher.services;

import java.util.regex.Pattern;

/** Info about path in the form /first/second/rest ignoring double slashes */
public class PathInfo {
    private static final Pattern PATH_SPLIT_REGEX = Pattern.compile("/+");

    /** [/first][/second]/rest */
    public final String all;

    /** Empty or no slashes */
    public final String first;

    /** Empty or no slashes */
    public final String second;

    /** empty or starts with slash */
    public final String afterFirst;

    /** empty or starts with slash */
    public final String afterSecond;


    public PathInfo(String pathIn) {
        final String path = pathIn.startsWith("/") ? pathIn : ("/" + pathIn);
        final String[] parts = PATH_SPLIT_REGEX.split(path, 4);

        first       = parts.length > 1 ? parts[1] : "";
        second      = parts.length > 2 ? parts[2] : "";
        afterFirst  = second.isEmpty() ? "" : ("/" + second + (parts.length > 3 ? "/" + parts[3] : ""));
        afterSecond = parts.length > 3 ? "/" + parts[3] : "";
        all         = "/" + (first.isEmpty() ? "" : first) + (second.isEmpty() ? "" : ("/" + second + afterSecond));
    }

    public String toString() {
        return all;
    }
}
