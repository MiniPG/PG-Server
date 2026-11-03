package com.sangyunpark99.pgserver.common.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * MySQL DATETIME(6)은 마이크로초까지만 저장하고 넘치는 자리는 반올림한다.
 * 시각을 만들 때 마이크로초로 잘라 두어, 응답에 나가는 값과 DB에 저장되는 값을 같게 한다.
 */
@Component
public class TimeProvider {

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
