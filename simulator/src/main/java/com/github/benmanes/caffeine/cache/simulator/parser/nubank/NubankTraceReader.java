package com.github.benmanes.caffeine.cache.simulator.parser.nubank;

import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

public class NubankTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  public NubankTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .map(line -> line.split(",", 2)[0])
        .mapToLong(Long::parseLong);
  }
}
