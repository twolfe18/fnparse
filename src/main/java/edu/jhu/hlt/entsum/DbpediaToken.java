package edu.jhu.hlt.entsum;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A token in a *.ttl file, e.g. "<http://dbpedia.org/resource/Langenstein_Castle>".
 * Handles some basic parsing like figuring out the type and stripping brackets.
 *
 * @author travis
 */
public class DbpediaToken {
  public static final String INT_TYPE_STR = "^^<http://www.w3.org/2001/XMLSchema#integer>";
  public static final String DATE_TYPE_STR = "^^<http://www.w3.org/2001/XMLSchema#date>";
  public static final String EN_TYPE_STR = "@en";
  
  enum Type {
    DBPEDIA_ENTITY,
    INTEGER,
    STRING_ENGLISH,
    DATE,
    OTHER,
//    STRING_OTHER,
  }
  
  /** Boundaries of entire token */
  public final int start, end;
  public final Type type;
  /**
   * A substring of [start,end) which represents the value.
   * e.g. substring(start,end) == "\"Perigee\"@en" and representation = "Perigee"
   */
  private String value;
  
  public DbpediaToken(Type type, String value) {
    this.type = type;
    this.value = value;
    this.start = -3;
    this.end = -2;
  }
  
  public DbpediaToken(String source, int start) {
    this.start = start;
    char s = source.charAt(start);
    if (s == '<') {
      type = Type.DBPEDIA_ENTITY;
      this.end = source.indexOf('>', start+1)+1;
      assert this.end > start;
      value = source.substring(start+1, this.end-1);
    } else if (s == '"') {
      int close = source.indexOf('"', start+1);
//      while (source.charAt(close-1) == '\\')
//        close = source.indexOf('"', close+1);
      assert close > start : "start=" + start + " close=" + close;
      value = source.substring(start+1, close);
      if (source.startsWith(INT_TYPE_STR, close+1)) {
        this.type = Type.INTEGER;
        this.end = close + INT_TYPE_STR.length() + 1;
      } else if (source.startsWith(EN_TYPE_STR, close+1)) {
        this.type = Type.STRING_ENGLISH;
        this.end = close + EN_TYPE_STR.length() + 1;
      } else if (source.startsWith(DATE_TYPE_STR, close+1)) {
        this.type = Type.DATE;
        this.end = close + DATE_TYPE_STR.length() + 1;
      } else {
        this.type = Type.OTHER;
        int space = source.indexOf(' ', close+1);
        this.end = space;
      }
    } else {
      throw new RuntimeException("can't parse: " + source.substring(start));
    }

//    System.out.println("this: " + toString());
//    System.out.println("post: " + source.substring(end));
//    System.out.println("inside: " + source.substring(start, end));
  }

  public String getValue() {
    return value;
  }
  
  @Override
  public String toString() {
    return "(DbpediaToken " + type + " " + value + ")";
  }
  
  @Override
  public int hashCode() {
    int h = value.hashCode();
    return Hash.mix(h, type.ordinal());
  }
  
  @Override
  public boolean equals(Object other) {
    if (other instanceof DbpediaToken) {
      DbpediaToken t = (DbpediaToken) other;
      return type == t.type && value.equals(t.value);
    }
    return false;
  }
  
  public static void main(String[] args) {
    String a = "\\\"\"";
    System.out.println(a);
    System.out.println(a.indexOf("[^\"]\""));
  }
}
