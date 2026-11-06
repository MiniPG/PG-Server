package com.sangyunpark99.pgserver.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.PairExtractor;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.structured.ContextPairs;
import org.springframework.boot.logging.structured.JsonWriterStructuredLogFormatter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import java.nio.charset.Charset;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class LogstashJsonFormatter extends JsonWriterStructuredLogFormatter<ILoggingEvent> {

    private static final PairExtractor<KeyValuePair> KEY_VALUE_PAIRS = PairExtractor.of((pair) -> pair.key,
            (pair) -> pair.value);

    public LogstashJsonFormatter(StackTracePrinter stackTracePrinter, ContextPairs contextPairs,
            ThrowableProxyConverter throwableProxyConverter, StructuredLoggingJsonMembersCustomizer<?> customizer) {
        super((members) -> members(stackTracePrinter, contextPairs, throwableProxyConverter, members), customizer);
    }

    @Override
    public byte[] formatAsBytes(ILoggingEvent event, Charset charset) {
        return format(event).getBytes(charset);
    }

    private static void members(StackTracePrinter stackTracePrinter, ContextPairs contextPairs,
            ThrowableProxyConverter throwableProxyConverter, JsonWriter.Members<ILoggingEvent> members) {
        members.add("@timestamp", ILoggingEvent::getInstant).as(LogstashJsonFormatter::asTimestamp);
        members.add("@version", "1");
        members.add("message", ILoggingEvent::getFormattedMessage);
        members.add("logger_name", ILoggingEvent::getLoggerName);
        members.add("thread_name", ILoggingEvent::getThreadName);
        members.add("level", ILoggingEvent::getLevel);
        members.add("level_value", ILoggingEvent::getLevel).as(Level::toInt);
        members.add().usingPairs(contextPairs.flat("_", (pairs) -> {
            pairs.addMapEntries(ILoggingEvent::getMDCPropertyMap);
            pairs.add(ILoggingEvent::getKeyValuePairs, KEY_VALUE_PAIRS);
        }));
        members.add("tags", ILoggingEvent::getMarkerList)
                .whenNotNull()
                .as(LogstashJsonFormatter::markers)
                .whenNotEmpty();
        Function<ILoggingEvent, Object> throwableProxy = (event) -> (event != null) ? event.getThrowableProxy() : null;
        members.add("stack_trace", (event) -> event)
                .whenNotNull(throwableProxy)
                .as((event) -> stackTrace(stackTracePrinter, throwableProxyConverter, event));
    }

    private static String stackTrace(StackTracePrinter stackTracePrinter,
            ThrowableProxyConverter throwableProxyConverter, ILoggingEvent event) {
        if (stackTracePrinter != null && event.getThrowableProxy() instanceof ThrowableProxy proxy) {
            return stackTracePrinter.printStackTraceToString(proxy.getThrowable());
        }
        return throwableProxyConverter.convert(event);
    }

    private static String asTimestamp(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    private static Set<String> markers(List<Marker> markers) {
        Set<String> result = new LinkedHashSet<>();
        addMarkers(result, markers.iterator());
        return result;
    }

    @SuppressWarnings("deprecation")
    private static void addMarkers(Set<String> result, Iterator<Marker> iterator) {
        while (iterator.hasNext()) {
            Marker marker = iterator.next();
            result.add(marker.getName());
            if (marker.hasReferences()) {
                addMarkers(result, marker.iterator());
            }
        }
    }
}
