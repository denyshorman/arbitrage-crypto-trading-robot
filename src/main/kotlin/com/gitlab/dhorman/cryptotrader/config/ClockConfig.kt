package com.gitlab.dhorman.cryptotrader.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
    @Bean
    fun systemClock(): Clock {
        return Clock.systemDefaultZone()
    }
}
