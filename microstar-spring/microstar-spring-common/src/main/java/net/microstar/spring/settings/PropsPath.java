package net.microstar.spring.settings;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class PropsPath {
    private static final Pattern IS_NUM = Pattern.compile("^\\d+$");
    private final String originalPath;
    private final List<String> path;

    public boolean        equals(final Object o) { return o instanceof PropsPath other && path.equals(other.path); }
    public int            hashCode() { return Objects.hashCode(path); }
    public boolean        isEmpty()  { return path.isEmpty(); }
    public String         head()     { return isEmpty() ? "" : path.get(0); }
    public PropsPath      tail()     { return isEmpty() ? this : new PropsPath(originalPath, path.subList(1, path.size())); }
    public String         original() { return originalPath; }
    public String         toString() {
        return path.stream()
            .map(p -> p.contains(".") ? (".[" + p + "]") : IS_NUM.matcher(p).matches() ? ("[" + p + "]") : ("." + p))
            .collect(Collectors.joining("")).replaceAll("^\\.|\\.$", "");
    }
    public List<String>   raw()      { return List.copyOf(path); }
    public PropsPath      base()     {
        int n = 0;
        final PropsPath origin = PropsPath.of(originalPath);
        while(n < path.size() && n < origin.path.size() && path.get(path.size()-n-1).equals(origin.path.get(origin.path.size()-n-1))) n++;
        final List<String> basePath = origin.path.subList(0, origin.path.size() - n);
        final String basePathText = basePath.stream().map(s -> s.contains(".") ? "[" + s + "]" : s).collect(Collectors.joining("."));
        return new PropsPath(basePathText, basePath);
    }

    /** Call handler for each property path element or until null is returned */
    public @Nullable Object visit(Object in, BiFunction<Object,PropsPath,Object> handler) {
        Object obj = in;
        PropsPath visitor = new PropsPath(originalPath, path);
        while(!visitor.isEmpty() && obj != null) {
            obj = handler.apply(obj, visitor);
            visitor = visitor.tail();
        }
        return obj;
    }

    public static PropsPath of(String path) {
        if(path.contains("/")) return new PropsPath(path, List.of(path));
        final List<String> parts = new ArrayList<>();
        String key = path;

        while (key.length() > 0) {
            // split into head and tail where head is either [a.dotted.name] or until next dot
            final String keyHead;
            final String keyTail;
            final int nextBlock = Optional.of(key.indexOf('[')).filter(n -> n >= 0).orElse(key.length());
            final int nextDot   = Optional.of(key.indexOf('.')).filter(n -> n >= 0).orElse(key.length());
            final int nextSplit = Math.min(nextBlock, nextDot);
            final boolean dotNext = nextSplit == nextDot && nextDot < key.length();

            if (nextBlock == 0) {
                final int endBlock = Optional.of(key.indexOf(']')).filter(n -> n >= 0).orElse(key.length());
                keyHead = key.substring(1, endBlock);
                keyTail = Optional.of(key.substring(Math.min(endBlock + 1, key.length()))).map(s -> s.startsWith(".") ? s.substring(1) : s).orElse("");
            } else {
                keyHead = key.substring(0, nextSplit);
                keyTail = key.substring(nextSplit + (dotNext ? 1 : 0));
            }
            parts.add(keyHead);
            key = keyTail;
        }
        if(path.endsWith(".")) parts.add("");
        return new PropsPath(path, parts);
    }
}
