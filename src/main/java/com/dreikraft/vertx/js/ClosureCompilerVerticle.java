package com.dreikraft.vertx.js;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.*;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.eventbus.ReplyException;
import org.vertx.java.core.eventbus.ReplyFailure;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Compiles and minifies Javascript files with the Google Closure Compiler.
 *
 * @author jansolo
 */
public class ClosureCompilerVerticle extends BusModBase {

    public static final String ADDRESS_BASE = ClosureCompilerVerticle.class.getPackage().getName();
    public static final String ADDRESS_COMPILE = ADDRESS_BASE + "/compile";
    public static final String JS_SOURCE_FILES = "jsSourceFiles";
    public static final String JS_COMPILED_FILE = "jsCompiledFile";
    public static final String CONFIG_COMPILE_ON_START = "compileOnStart";

    private static final String ERR_MSG_JS_COMPILE_FAILED = "failed to compile js: %1$s";
    private static final String ERR_MSG_JS_WRITE_FAILED = "failed to write compiled js file %1$s: %2$s";
    private static final String ERR_MSG_INVALID_COMPILE_MESSAGE = "invalid message at %1$s: %2$s";
    private static final String ERR_MSG_UNEXPECTED = "unexpected exception %1$s while processing message %2$s";

    @Override
    public void start(final Future<Void> startedResult) {

        // initialize members
        super.start();

        // register event bus addresses
        logger.info(String.format("registering %1$s ...", ADDRESS_COMPILE));
        eb.registerHandler(ADDRESS_COMPILE, new CompileHandler(), new AsyncResultHandler<Void>() {
            @Override
            public void handle(AsyncResult<Void> registerResult) {
                if (registerResult.succeeded()) {
                    logger.info(String.format("successfully registered %1$s", ADDRESS_COMPILE));
                    if (getOptionalBooleanConfig(CONFIG_COMPILE_ON_START, true)) {
                        final JsonObject compileMsg = new JsonObject();
                        compileMsg.putString(JS_COMPILED_FILE, getOptionalStringConfig(JS_COMPILED_FILE, null));
                        compileMsg.putArray(JS_SOURCE_FILES, getOptionalArrayConfig(JS_SOURCE_FILES, new JsonArray()));
                        logger.info(String.format("starting compilation %1$s ...", compileMsg.encodePrettily()));
                        eb.send(ADDRESS_COMPILE, compileMsg, new Handler<Message<JsonObject>>() {
                            @Override
                            public void handle(final Message<JsonObject> compileResult) {
                                final JsonObject resultBody = compileResult.body();
                                if ("ok".equals(resultBody.getField("status"))) {
                                    startedResult.setResult(null);
                                } else {
                                    startedResult.setFailure(new VertxException(resultBody.getString("message")));
                                }
                            }
                        });
                    } else {
                        startedResult.setResult(null);
                    }
                } else {
                    startedResult.setFailure(registerResult.cause());
                }
            }
        });
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
            try {
                final String jsCompiledFile = msgBody.getString(JS_COMPILED_FILE);
                final JsonArray jsSourceFilesArray = msgBody.getArray(JS_SOURCE_FILES);

                if (jsCompiledFile != null && jsSourceFilesArray != null && jsSourceFilesArray.size() > 0) {

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
                    final Iterator it = jsSourceFilesArray.iterator();
                    while (it.hasNext()) {
                        final String jsSourceFileName = (String) it.next();
                        try {
                            jsSourceFiles.add(SourceFile.fromFile(new File(
                                    Thread.currentThread().getContextClassLoader().getResource(jsSourceFileName)
                                            .toURI()
                            )));
                            logger.debug(String.format("adding js source file to compilation: %1$s",
                                    jsSourceFileName));
                        } catch (URISyntaxException | NullPointerException e) {
                            logger.error(String.format("failed to add js source file to compilation: %1$s",
                                    jsSourceFileName), e);
                        }
                    }
                    logger.info(String.format("starting compilation of %1$s with options %2$s ...",
                            jsSourceFiles.toString(), options.toString()));
                    final Result compileResult = compiler.compile(externs, jsSourceFiles, options);
                    if (compileResult.success) {
                        if (logger.isDebugEnabled())
                            logger.debug(String.format("successfully compiled %1$s", jsSourceFiles.toString()));
                        final String outDir = extractDir(jsCompiledFile);
                        if (logger.isDebugEnabled())
                            logger.debug(String.format("creating js output directory %1$s ...", outDir));
                        vertx.fileSystem().mkdirSync(outDir, true);
                        if (logger.isDebugEnabled()) {
                            logger.debug(String.format("successfully created js output directory %1$s", outDir));
                            logger.debug(String.format("writing compiled js file %1$s ...", jsCompiledFile));
                        }
                        vertx.fileSystem().writeFile(jsCompiledFile, new Buffer(compiler.toSource(), "UTF-8"),
                                new AsyncResultHandler<Void>() {
                                    @Override
                                    public void handle(AsyncResult<Void> writeResult) {
                                        if (writeResult.succeeded()) {
                                            if (logger.isDebugEnabled())
                                                logger.debug(String.format("successfully wrote compiled js file %1$s",
                                                        jsCompiledFile));
                                            sendOK(compileMessage, new JsonObject().putString("message",
                                                    String.format("successfully compiled %1$d " +
                                                            "javascript files", jsSourceFilesArray.size())
                                            ));
                                        } else {
                                            sendError(compileMessage, String.format(ERR_MSG_JS_WRITE_FAILED,
                                                            jsCompiledFile, writeResult.cause().getMessage()),
                                                    (Exception) writeResult.cause()
                                            );
                                        }
                                    }
                                }
                        );
                    } else {
                        final Exception rex = new ReplyException(ReplyFailure.RECIPIENT_FAILURE,
                                String.format(ERR_MSG_JS_COMPILE_FAILED, Arrays.toString(compileResult.errors)));
                        sendError(compileMessage, rex.getMessage(), rex);
                    }
                } else {
                    final Exception rex = new ReplyException(ReplyFailure.RECIPIENT_FAILURE,
                            String.format(ERR_MSG_INVALID_COMPILE_MESSAGE,
                                    compileMessage.address(), msgBody != null ? msgBody.encodePrettily() : null)
                    );
                    sendError(compileMessage, rex.getMessage(), rex);
                }
            } catch (RuntimeException ex) {
                sendError(compileMessage, String.format(ERR_MSG_UNEXPECTED, ex.getMessage(), msgBody), ex);
            }
        }
    }
}
