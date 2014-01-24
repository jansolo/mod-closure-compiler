package com.dreikraft.vertx.js;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Created by jan_solo on 23.01.14.
 */
public class ClosureCompilerVerticle extends BusModBase {

    public static final String ADDRESS_BASE = ClosureCompilerVerticle.class.getName();
    public static final String ADDRESS_COMPILE = ADDRESS_BASE + "/compile";
    public static final long REPLY_TIMEOUT = 30 * 1000;
    public static final String JS_SOURCE_FILES = "jsSourceFiles";
    public static final String JS_COMPILED_FILE = "jsCompiledFile";
    public static final int ERR_CODE_BASE = 300;
    public static final int ERR_CODE_JS_COMPILE_FAILED = ERR_CODE_BASE;
    public static final String ERR_MSG_JS_COMPILE_FAILED = "failed to compile js: %1$s";
    public static final int ERR_CODE_JS_WRITE_FAILED = ERR_CODE_BASE + 1;
    public static final String ERR_MSG_JS_WRITE_FAILED = "failed to write compiled js file %1$s: %2$s";

    @Override
    public void start(Future<Void> startedResult) {
        super.start(startedResult);
        logger.info(String.format("starting verticle %1$s ...", this.getClass().getName()));

        logger.info(String.format("registering handler %1$s", ADDRESS_COMPILE));
        eb.registerHandler(ADDRESS_COMPILE, new CompileHandler());
    }

    private String extractDir(String target) {
        final String[] targetDirParts = target.split("/");
        final StringBuilder dirBuilder = new StringBuilder();
        if (targetDirParts.length > 1) {
            for (int i = 0; i < targetDirParts.length - 1; i++) {
                dirBuilder.append(targetDirParts[i]).append(i < targetDirParts.length - 2 ? "/" : "");
            }
        }
        return dirBuilder.toString();
    }

    private class CompileHandler implements Handler<Message<JsonObject>> {
        @Override
        public void handle(final Message<JsonObject> compileMessage) {
            final JsonObject msgBody = compileMessage.body();
            // initialize compiler
            final Compiler compiler = new Compiler();
            final CompilerOptions options = new CompilerOptions();
            CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
            List<SourceFile> externs = new ArrayList<>();
            try {
                externs = CommandLineRunner.getDefaultExterns();
            } catch (IOException e) {
                logger.error("failed to load externs", e);
            }
            final List<SourceFile> jsSourceFiles = new ArrayList<>();
            final Iterator it = msgBody.getArray(JS_SOURCE_FILES).iterator();
            while (it.hasNext()) {
                final String jsSourceFileName = (String) it.next();
                try {
                    jsSourceFiles.add(SourceFile.fromFile(new File(Thread.currentThread().getContextClassLoader()
                            .getResource(jsSourceFileName).toURI())));
                    logger.info(String.format("compiling js source file: %1$s", jsSourceFileName));
                } catch (URISyntaxException | NullPointerException e) {
                    logger.error(String.format("failed to load js source file %1$s", jsSourceFileName), e);
                }
            }
            final Result compileResult = compiler.compile(externs, jsSourceFiles, options);
            if (compileResult.success) {
                final String jsCompiledFile = msgBody.getString(JS_COMPILED_FILE);
                vertx.fileSystem().mkdirSync(extractDir(jsCompiledFile), true);
                vertx.fileSystem().writeFile(jsCompiledFile, new Buffer(compiler.toSource(), "UTF-8"),
                        new AsyncResultHandler<Void>() {
                            @Override
                            public void handle(AsyncResult<Void> writeResult) {
                                if (writeResult.succeeded()) {
                                    logger.info(String.format("successfully compiled js files to $1%s", jsCompiledFile));
                                    compileMessage.reply();
                                } else {
                                    compileMessage.fail(ERR_CODE_JS_WRITE_FAILED, String.format(
                                            ERR_MSG_JS_WRITE_FAILED, jsCompiledFile,
                                            writeResult.cause().getMessage()));
                                }
                            }
                        });
            } else {
                compileMessage.fail(ERR_CODE_JS_COMPILE_FAILED, String.format(ERR_MSG_JS_COMPILE_FAILED,
                        Arrays.toString(compileResult.errors)));
            }
        }
    }
}
