package net.microstar.spring;

import com.google.common.collect.ImmutableMap;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.http.MediaType;

import java.util.Optional;

/** Get content type for a given filename and/or file data. I'd expect this to be basic
  * functionality of Spring, but apparently it isn't.<p>
  *
  * The JDK methods (Files.probeContentType, Connection.getContentType,
  * URLConnection.guessContentTypeFromName, FileNameMap.getContentTypeFor) apparently
  * are either very buggy or have very limited support for even common types.
  * So these won't be used here. Instead, a list of common types is defined here
  * and can be extended in the configuration when needed.
  */
public final class ContentTypes {
    private ContentTypes() {}

    private static final DynamicPropertiesRef<ContentTypesProperties> props = DynamicPropertiesRef.of(ContentTypesProperties.class);
    private static final ImmutableMap<String,String> DEFAULT_EXT_TO_TYPE = ImmutableUtil.mapOf(
        "bmp",   "image/bmp",
        "bz",    "application/x-bzip",
        "bz2",   "application/x-bzip2",
        "css",   "text/css",
        "csv",   "text/csv",
        "gif",   "image/gif",
        "doc",   "application/msword",
        "gz",    "application/gzip",
        "html",  "text/html",
        "htm",   "text/html",
        "ico",   "image/vnd.microsoft.icon",
        "jar",   "application/java-archive",
        "jpg",   "image/jpeg",
        "jpeg",  "image/jpeg",
        "js",    "text/javascript",
        "json",  "application/json",
        "md",    "text/markdown",
        "mjs",   "text/javascript",
        "pdf",   "application/pdf",
        "png",   "image/png",
        "ppt",   "application/vnd.ms-powerpoint",
        "rar",   "application/vnd.rar",
        "rtf",   "application/rtf",
        "scss",  "text/scss",
        "svg",   "image/svg+xml",
        "tar",   "application/x-tar",
        "ttf",   "font/ttf",
        "txt",   "text/plain",
        "text",  "text/plain",
        "woff",  "font/woff",
        "woff2", "font/woff2",
        "xhtml", "application/xhtml+xml",
        "xls",   "application/vnd.ms-excel",
        "xml",   "application/xml",
        "yaml",  "application/yaml",
        "yml",   "application/yaml",
        "zip",   "application/zip",
        ".7z",   "application/x-7z-compressed"
    );

    /** When parameter is a filename (contains a dot), the part after the dot is used */
    public static String typeOfName(String extOrName) {
        final int dotAt = extOrName.lastIndexOf('.');
        final String ext = dotAt < 0 ? extOrName : extOrName.substring(dotAt + 1);
        return Optional
            .of(props.get().extToType)
            .map(extToType -> extToType.get(ext))
            .or(() -> Optional.ofNullable(DEFAULT_EXT_TO_TYPE.get(ext)))
            .orElse(props.get().defaultType);
    }

    /** When parameter is a filename (contains a dot), the part after the dot is used */
    public static MediaType mediaTypeOfName(String ext) {
        return MediaType.valueOf(typeOfName(ext));
    }
}
