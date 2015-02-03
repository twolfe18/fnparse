package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class InputStreamGobbler extends Thread {
  private InputStream is;
  private List<String> lines;
  public InputStreamGobbler(InputStream is) {
    this.is = is;
    this.lines = new ArrayList<>();
  }
  @Override
  public void run() {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
      String line;
      while ((line = r.readLine()) != null)
        lines.add(line);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  public List<String> getLines() {
    return lines;
  }
}