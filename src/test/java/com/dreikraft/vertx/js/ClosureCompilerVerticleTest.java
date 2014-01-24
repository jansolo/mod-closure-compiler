package com.dreikraft.vertx.js;

import org.junit.Test;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.testtools.TestVerticle;
import org.vertx.testtools.VertxAssert;

/**
 * Created by jan_solo on 09.01.14.
 */
public class ClosureCompilerVerticleTest extends TestVerticle {

    @Override
    public void start() {
        initialize();
        container.deployModule(System.getProperty("vertx.modulename"), new AsyncResultHandler<String>() {
            @Override
            public void handle(AsyncResult<String> startResult) {
                if (startResult.failed()) {
                    container.logger().error(startResult.cause().getMessage(), startResult.cause());
                }
                VertxAssert.assertTrue(startResult.succeeded());
                VertxAssert.assertNotNull("deploymentID should not be null", startResult.result());
                startTests();
            }
        });
    }

    @Test
    public void testCompileValidJs() {
        final JsonObject msg = new JsonObject();
        final JsonArray jsSourceFiles = new JsonArray();
        jsSourceFiles.addString("js/valid.js");
        msg.putArray(ClosureCompilerVerticle.JS_SOURCE_FILES, jsSourceFiles);
        msg.putString(ClosureCompilerVerticle.JS_COMPILED_FILE, "js_min/valid.js");

        vertx.eventBus().sendWithTimeout(ClosureCompilerVerticle.ADDRESS_COMPILE, msg, ClosureCompilerVerticle.REPLY_TIMEOUT,
                new AsyncResultHandler<Message<Void>>() {
                    @Override
                    public void handle(AsyncResult<Message<Void>> result) {
                        if (result.failed()) {
                            VertxAssert.fail(result.cause().getMessage());
                        }
                        VertxAssert.assertTrue(vertx.fileSystem().existsSync("js_min/valid.js"));
                        vertx.fileSystem().deleteSync("js_min/valid.js");
                        VertxAssert.testComplete();
                    }
                });
    }

    @Test
    public void testCompileInValidJs() {
        final JsonObject msg = new JsonObject();
        final JsonArray jsSourceFiles = new JsonArray();
        jsSourceFiles.addString("js/invalid.js");
        msg.putArray(ClosureCompilerVerticle.JS_SOURCE_FILES, jsSourceFiles);
        msg.putString(ClosureCompilerVerticle.JS_COMPILED_FILE, "js_min/invalid.js");

        vertx.eventBus().sendWithTimeout(ClosureCompilerVerticle.ADDRESS_COMPILE, msg, ClosureCompilerVerticle.REPLY_TIMEOUT,
                new AsyncResultHandler<Message<Void>>() {
                    @Override
                    public void handle(AsyncResult<Message<Void>> result) {
                        if (result.succeeded()) {
                            VertxAssert.fail("should not compile");
                        } else {
                            container.logger().info(result.cause());
                        }
                        VertxAssert.testComplete();
                    }
                });
    }

}
