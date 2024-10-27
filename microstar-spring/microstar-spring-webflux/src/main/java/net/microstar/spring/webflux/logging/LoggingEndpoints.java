package net.microstar.spring.webflux.logging;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.logging.LogFiles;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping(path = "/logging", produces = "text/plain")
@AllArgsConstructor
public class LoggingEndpoints {
    private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
    private static final int BUFFER_SIZE = 16 * 1024;

    @GetMapping("/current")
    public Flux<DataBuffer> getCurrentLog() {
        return LogFiles.getInstance().getCurrentPath()
            .map(path -> DataBufferUtils.read(path, bufferFactory, BUFFER_SIZE))
            .orElseGet(Flux::empty);
    }
}
