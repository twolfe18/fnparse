package edu.jhu.hlt.uberts.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import edu.jhu.hlt.tutils.ExperimentProperties;

/**
 * E.g. used to strip the frame sense off of argument4(t,f,s,k) facts.
 * 
 * If you pass in a gzipped file (ends with ".gz"), the this will also create
 * a "gunzip -c" process before the perl one.
 *
 * @author travis
 */
public class PerlRegexFileInputStream {

  private String regex;
  private File source;
//  private InputStream source;
  // It seems that you can't use an InputStream as a ProcessBuilder.Redirect...
  // I guess because Redirect is a wrapper around Unix functionality rather than
  // Java functionality...

  public PerlRegexFileInputStream(File input, String regex) {
    this.source = input;
    this.regex = regex;
  }

  public InputStream start() throws IOException {
    ProcessBuilder pb;
    if (source.getName().toLowerCase().endsWith(".gz")) {
      String c = "gunzip -c " + source.getPath() + " | perl -pe '" + regex + "'";
      pb = new ProcessBuilder("/bin/sh", "-c", c);
    } else {
      pb = new ProcessBuilder("perl", "-pe", regex);
      pb.redirectInput(source);
    }
    Process p = pb.start();
    return p.getInputStream();
  }

  public InputStream startOrBlowup() {
    try {
      return start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    File f = new File("data/srl-reldata/grammar/srl-grammar-noFilter-fineFrame.trans");
    f = config.getExistingFile("input", f);
    String regex = config.getString("dataRegex", "s/[aeiou]/X/g");
    PerlRegexFileInputStream p = new PerlRegexFileInputStream(f, regex);
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.start()))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        System.out.println(line);
      }
    }
  }
}
