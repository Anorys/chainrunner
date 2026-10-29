package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.StepFields;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.core.Template;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a file ({@code path}, relative to the configuration) and extracts values from it: with
 * {@code format: json}, JSON pointers; with {@code csv}, columns ({@code column} for the first row,
 * {@code column[]} for every row); with {@code text}, {@code content} (the whole text) or {@code lines}
 * (the lines that are not blank). The format comes from the extension when left out.
 */
public record FileStep(String path, Format format, char delimiter) implements Step {

    public enum Format {
        /** From the file's extension: json, csv, else text. */
        AUTO, JSON, CSV, TEXT
    }

    /** {@code type: file}. */
    public static final class Kind implements StepKind {

        @Override
        public String type() {
            return "file";
        }

        @Override
        public Step parse(StepFields f) {
            String delimiter = f.text("delimiter", "");
            if (delimiter.length() > 1) {
                throw f.error("delimiter: one character expected, got '" + delimiter + "'");
            }
            return new FileStep(f.required("path", "for a file step"), f.choice("format", Format.AUTO),
                    delimiter.isEmpty() ? 0 : delimiter.charAt(0));
        }
    }

    @Override
    public String defaultName() {
        return "read " + path;
    }

    @Override
    public String label() {
        return "FILE";
    }

    @Override
    public String summary() {
        return "READ " + path;
    }

    @Override
    public String requestTitle() {
        return "FILE";
    }

    @Override
    public String waiting() {
        return "reading…";
    }

    @Override
    public List<Detail> details() {
        return format == Format.AUTO ? List.of() : List.of(new Detail("format", format.name().toLowerCase(Locale.ROOT)));
    }

    @Override
    public String outcome(CallResult result) {
        return result.status() == Status.OK ? "read" : null;
    }

    /** {@code READ} and the file's full path. */
    @Override
    public Request request(StepContext context, Call call, Map<String, Object> vars) {
        Path file = context.options().baseDir().resolve(Template.text(path, vars)).normalize();
        return new Request("READ", file.toString(), Map.of(), null);
    }

    @Override
    public CallResult run(StepContext context, Call call, Request request, Map<String, Object> vars) {
        long start = System.nanoTime();
        Path file = Path.of(request.url());
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return CallResult.failed(request, null, Runner.since(start), null, "no such file: " + file, List.of(), false);
        } catch (MalformedInputException e) {
            return CallResult.failed(request, null, Runner.since(start), null, "not a UTF-8 text file: " + file, List.of(), false);
        } catch (IOException e) {
            return CallResult.failed(request, null, Runner.since(start), null, "cannot read " + file + ": " + e.getMessage(), List.of(), false);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        Format actual = format != Format.AUTO ? format : of(file);
        String note = Runner.size(text.getBytes(StandardCharsets.UTF_8).length) + ", " + actual.name().toLowerCase(Locale.ROOT);
        Map<String, Object> extracted = new LinkedHashMap<>();
        String shown = context.display(text, null);
        switch (actual) {
            case JSON -> {
                for (Map.Entry<String, String> e : call.extract().entrySet()) {
                    Object value = Json.value(text, e.getValue());
                    if (value == null) {
                        return CallResult.failed(request, null, Runner.since(start), shown,
                                "extract " + e.getKey() + ": " + e.getValue() + " is not in the file", List.of(note), false);
                    }
                    extracted.put(e.getKey(), value);
                }
            }
            case CSV -> {
                List<Map<String, Object>> rows = csv(text, delimiter);
                note = note + ", " + rows.size() + (rows.size() == 1 ? " row" : " rows");
                try {
                    extracted.putAll(Rows.extract(rows, call.extract()));
                } catch (Rows.ExtractException e) {
                    return CallResult.failed(request, null, Runner.since(start), shown, e.getMessage(), List.of(note), false);
                }
            }
            default -> {
                for (Map.Entry<String, String> e : call.extract().entrySet()) {
                    switch (e.getValue().strip().toLowerCase(Locale.ROOT)) {
                        case "content" -> extracted.put(e.getKey(), text.strip());
                        case "lines" -> extracted.put(e.getKey(), text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
                        default -> {
                            return CallResult.failed(request, null, Runner.since(start), shown, "extract " + e.getKey()
                                    + ": '" + e.getValue() + "' is unknown for a text file (content or lines)", List.of(note), false);
                        }
                    }
                }
            }
        }
        return new CallResult(Status.OK, request, null, Runner.since(start), shown, null, Map.copyOf(extracted), List.of(note), false);
    }

    private static Format of(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") ? Format.JSON : name.endsWith(".csv") ? Format.CSV : Format.TEXT;
    }

    /**
     * The rows of a CSV text, the first line naming the columns; fields may be quoted ({@code "a, b"},
     * {@code ""} for a quote). Without a delimiter: {@code ;} when the header has one and no comma, else
     * {@code ,}. Values are texts, empty fields null.
     */
    static List<Map<String, Object>> csv(String text, char delimiter) {
        List<List<String>> records = records(text, delimiter != 0 ? delimiter : guess(text));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (records.isEmpty()) {
            return rows;
        }
        List<String> header = records.get(0).stream().map(String::strip).toList();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.size() == 1 && record.get(0).isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                String value = i < record.size() ? record.get(i).strip() : "";
                row.put(header.get(i), value.isEmpty() ? null : value);
            }
            rows.add(row);
        }
        return rows;
    }

    private static char guess(String text) {
        String header = text.lines().findFirst().orElse("");
        return header.indexOf(';') >= 0 && header.indexOf(',') < 0 ? ';' : ',';
    }

    private static List<List<String>> records(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.toString().isBlank()) {
                field.setLength(0);
                quoted = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
