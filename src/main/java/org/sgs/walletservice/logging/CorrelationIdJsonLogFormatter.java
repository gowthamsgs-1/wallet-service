package org.sgs.walletservice.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import java.util.List;

/**
 * Structured (JSON) log format kept deliberately lean.
 *
 * <p>Field order follows the order members are declared below: timestamp, level,
 * {@code correlation_id}, logger, thread, message, then the key-values attached to domain
 * events ({@code event}, {@code transfer_id}, ...), then any error details.
 *
 * <p>Registered via {@code logging.structured.format.console} in application.properties.
 */
public class CorrelationIdJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final JsonWriter<ILoggingEvent> writer;

    public CorrelationIdJsonLogFormatter() {
        this.writer = JsonWriter.<ILoggingEvent>of(members -> {
            members.add("@timestamp", event -> event.getInstant().toString());
            members.add("level", event -> event.getLevel().toString());

            // Third field, so a request is identifiable without scanning the whole object.
            members.add(CorrelationIdFilter.CORRELATION_ID_KEY,
                            event -> event.getMDCPropertyMap().get(CorrelationIdFilter.CORRELATION_ID_KEY))
                    .whenHasLength();

            members.add("logger", ILoggingEvent::getLoggerName);
            members.add("thread", ILoggingEvent::getThreadName);
            members.add("message", ILoggingEvent::getFormattedMessage);

            // Key-values attached with logger.atInfo().addKeyValue(...) - the domain event fields.
            members.add().usingExtractedPairs(CorrelationIdJsonLogFormatter::keyValuePairs,
                    pair -> pair.key, pair -> pair.value);

            members.add("error_type", event -> throwableField(event, IThrowableProxy::getClassName)).whenNotNull();
            members.add("error_message", event -> throwableField(event, IThrowableProxy::getMessage)).whenNotNull();
            members.add("error_stack_trace", CorrelationIdJsonLogFormatter::stackTrace).whenNotNull();
        }).withNewLineAtEnd();
    }

    @Override
    public String format(ILoggingEvent event) {
        return this.writer.writeToString(event);
    }

    private static void keyValuePairs(ILoggingEvent event, java.util.function.Consumer<KeyValuePair> consumer) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(consumer);
        }
    }

    private static String throwableField(ILoggingEvent event,
                                         java.util.function.Function<IThrowableProxy, String> extractor) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return (throwable != null) ? extractor.apply(throwable) : null;
    }

    private static String stackTrace(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return (throwable != null) ? ThrowableProxyUtil.asString(throwable) : null;
    }
}
