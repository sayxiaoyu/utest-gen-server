package com.utest.gen.controller;

import com.utest.gen.UTestGenApplication;
import com.utest.gen.service.OpenCodeAutoGenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest(classes = UTestGenApplication.class)
public class UTest {
    @Autowired
    private OpenCodeAutoGenService openCodeAutoGenService;
    @Test
    void testGenerate() {
        openCodeAutoGenService.autoGenerate("D:\\JAVA\\ai\\utest-gen-server\\src\\main\\java\\com\\utest\\gen\\opencode\\OpenCodeManager.java",
                List.of("onApplicationReady", "start","stop","getServerUrl","isRunning"));
    }
}
