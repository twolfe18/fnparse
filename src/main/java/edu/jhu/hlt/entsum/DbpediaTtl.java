package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Iterator;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Parses .ttl lines. Not meant to be super efficient for storage,
 * use an alphabet/hash and ints for that.
 *
 * @author travis
 */
public class DbpediaTtl implements Serializable {
  private static final long serialVersionUID = -3934387473246714733L;

//  private String subj, verb, obj;
  private DbpediaToken subj, verb, obj;
  public final String line;
  public final boolean valid;

  public DbpediaTtl(DbpediaToken subj, DbpediaToken verb, DbpediaToken obj, String line) {
    this.subj = subj;
    this.verb = verb;
    this.obj = obj;
    this.valid = true;
    this.line = line;
  }

  public DbpediaTtl(String line, boolean keepLine) {
//    int n = line.length();
//    assert line.charAt(n-2) == ' ';
//    assert line.charAt(n-1) == '.';
//    int s1 = line.indexOf("> <");
//    int s2 = line.indexOf("> ", s1+1);
//    subj = line.substring(1, s1);
//    verb = line.substring(s1+3, s2);
//    obj = line.substring(s2+2, n-2);
//    System.out.println("parsing: " + line);
    subj = new DbpediaToken(line, 0);
    verb = new DbpediaToken(line, subj.end+1);
    obj = new DbpediaToken(line, verb.end+1);
    String suf = line.substring(obj.end+1);
//    System.out.println("suf: " + suf);
    valid = suf.equalsIgnoreCase(".");
    this.line = keepLine ? line : null;
  }
  
//  public DbpediaTtl(String s, String v, String o) {
//    subj = s;
//    verb = v;
//    obj = o;
//  }
  
  public DbpediaToken subject() {
    return subj;
  }
  
  public DbpediaToken verb() {
    return verb;
  }
  
  public DbpediaToken object() {
    return obj;
  }
  
  @Override
  public int hashCode() {
    int h = 42;
    h = Hash.mix(h, subj.hashCode());
    h = Hash.mix(h, verb.hashCode());
    h = Hash.mix(h, obj.hashCode());
    return h;
  }
  
  @Override
  public boolean equals(Object other) {
    if (other instanceof DbpediaTtl) {
      DbpediaTtl d = (DbpediaTtl) other;
      return subj.equals(d.subj)
          && verb.equals(d.verb)
          && obj.equals(d.obj);
    }
    return false;
  }
  
  public String tsv() {
    return subj.getValue() + "\t" + verb.getValue() + "\t" + obj.getValue();
  }
  
  @Override
  public String toString() {
    return "(" + subj + " " + verb + " " + obj + ")";
  }
  
  /** Converts from "http://rdf.freebase.com/ns/m.0100j9wd" to "/m/0100j9wd" */
  public static String extractMidFromTtl(String obj) {
    int n = obj.length();
    assert obj.charAt(0) != '<';
    assert obj.charAt(n-1) != '>';
    String p = "/ns/m.";
    int i = obj.indexOf(p);
    assert i >= 0;
    i += p.length();
    return "/m/" + obj.substring(i, obj.length());
  }

  public static boolean isKbNode(String subj) {
    assert subj.charAt(0) != '<';
    assert subj.indexOf("https://") < 0;
    if (subj.startsWith("http://"))
      return true;
//    if (subj.startsWith("https://"))
//      return true;
//    if (subj.startsWith("<http://"))
//      return true;
//    if (subj.startsWith("<https://"))
//      return true;
    return false;
  }
  
//  public static Pair<String, String> extractValueAndType(String obj) {
//    if (obj.charAt(0) != '"')
//      throw new IllegalArgumentException();
//    
//    int q = obj.indexOf('"', 1);
//    String value = obj.substring(1, q);
//    String type = "na";
//    if (obj.startsWith("@", q+1)) {
//      type = obj.substring(q+2);
//    } else if (obj.startsWith("^^<", q+1)) {
//      type = obj.substring(q+4, obj.length()-1);
//    }
//    return new Pair<>(value, type);
//  }
  
  public static class LineIterator implements Iterator<DbpediaTtl>, AutoCloseable {
    private BufferedReader r;
//    private String cur;
    private DbpediaTtl cur;
    private int read, skip;
    public final boolean keepLines;
    
    public LineIterator(File f, boolean keepLines) throws IOException {
      this.keepLines = keepLines;
      r = FileUtil.getReader(f);
      advance();
    }
    
    private void advance() throws IOException {
      this.cur = null;
      while (this.cur == null) {
        String line = r.readLine();
        if (line == null)
          break;
        if (line.startsWith("#") || line.isEmpty()) {
          skip++;
          continue;
        }
        this.cur = new DbpediaTtl(line, keepLines);
        if (!this.cur.valid) {
          skip++;
          this.cur = null;
        }
      }
      if (this.cur != null)
        read++;
    }
    
    public double getProportionSkipped() {
      return ((double) skip) / (skip + read);
    }

    @Override
    public boolean hasNext() {
      return cur != null;
    }

    @Override
    public DbpediaTtl next() {
//      DbpediaTtl d = new DbpediaTtl(cur);
      DbpediaTtl d = cur;
      try {
        advance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return d;
    }

    @Override
    public void close() throws IOException {
      r.close();
    }
  }
  
  public static void main(String[] args) {
    String a = "<http://dbpedia.org/resource/Autism> <http://dbpedia.org/property/field> <http://dbpedia.org/resource/Psychiatry> .";
    String b = "<http://dbpedia.org/resource/Autism> <http://dbpedia.org/property/diseasesdb> \"1142\"^^<http://www.w3.org/2001/XMLSchema#integer> .";
    String c = "<http://dbpedia.org/resource/Autism> <http://dbpedia.org/property/icd> \"F84.0\"@en .";
    
    new DbpediaToken(a, 0);
//    DbpediaToken a1 = new DbpediaToken(a, 0);
//    System.out.println(a1);
//    System.out.println(a.substring(a1.end+1));

    System.out.println(new DbpediaTtl(a, false));
    System.out.println(new DbpediaTtl(b, false));
    System.out.println(new DbpediaTtl(c, false));
  }
}
