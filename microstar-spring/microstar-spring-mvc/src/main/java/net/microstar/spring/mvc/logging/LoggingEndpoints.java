package net.microstar.spring.mvc.logging;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.logging.LogFiles;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;

import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
@RestController
@RequestMapping(path = "/logging", produces = "text/plain")
@AllArgsConstructor
public class LoggingEndpoints {

    @GetMapping("/current")
    public ResponseEntity<InputStreamResource> getCurrentLog() {
        return LogFiles.getInstance().getCurrentPath()
            .flatMap(path -> noThrow(() -> {
                final InputStream inStream = new FileInputStream(path.toFile());
                final InputStreamResource inResource = new InputStreamResource(inStream);
                final HttpHeaders headers = new HttpHeaders();
                headers.setContentLength(Files.size(path));
                return new ResponseEntity<>(inResource, headers, HttpStatus.OK);
            })).orElseThrow(() -> new NotFoundException("No current log"));
    }
}
