package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@Slf4j
@ExtendWith(OutputCaptureExtension.class)
class MicroStarExceptionTest {

    @Test void shouldLogFromCallingClass(CapturedOutput output) {
        //noinspection ThrowableNotThrown
        new MicroStarException("Testing").log();
        assertThat(output.getOut(), containsString(getClass().getSimpleName()));
    }
}