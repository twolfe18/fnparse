package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigawordId implements Serializable {
  private static final long serialVersionUID = 5982404969811926170L;
  
  public static final Pattern PATTERN = Pattern.compile("([A-Z]+)_([A-Z]+)_(\\d{4})(\\d{2})(\\d{2})\\.(\\d+)");

  public final String raw;
  public final String source;
  public final String lang;
  public final int year;
  public final int month;
  public final int day;
  public final int idxInDay;
  
  public GigawordId(String id) {
    this.raw = id.toUpperCase();
    Matcher m = PATTERN.matcher(id);
    if (m.find()) {
      source = m.group(1);
      lang = m.group(2);
      year = Integer.parseInt(m.group(3));
      month = Integer.parseInt(m.group(4));
      day = Integer.parseInt(m.group(5));
      idxInDay = Integer.parseInt(m.group(6));
    } else {
      source = lang = null;
      year = month = day = idxInDay = -1;
    }
  }
  
  public boolean isValid() {
    return source != null;
  }
  
  public LocalDate toDate() {
    if (!isValid())
      return null;
    return LocalDate.of(year, month, day);
  }
  
  @Override
  public String toString() {
    if (isValid()) {
      return "(GW " + raw + ")";
    } else {
      return "(GW [invalid] " + raw + ")";
    }
  }
  
  @Override
  public boolean equals(Object other) {
    if (other instanceof GigawordId) {
      GigawordId g = (GigawordId) other;
      return raw.equals(g.raw);
    }
    return false;
  }

  public boolean matchesYear(GigawordId other) {
    return year >= 0 && year == other.year;
  }
  
  public boolean matchesMonth(GigawordId other) {
    return matchesYear(other) && month >= 0 && month == other.month;
  }
  
  public boolean matchesDay(GigawordId other) {
    return matchesMonth(other) && day >= 0 && day == other.day;
  }
  
  public boolean matchesLang(GigawordId other) {
    return lang != null && lang.equals(other.lang);
  }

  public boolean matchesSource(GigawordId other) {
    return matchesLang(other) && source != null && source.equals(other.source);
  }
  
  public static void main(String[] args) {
    List<String> ids = new ArrayList<>();
    ids.add("NYT_ENG_19941104.0002");
    ids.add("WPB_ENG_20100826.0077");
    ids.add("A Wikipedia Id (composer)");
    for (String i : ids) {
      GigawordId g = new GigawordId(i);
//      System.out.println(g + "\t" + g.idxInDay);
//      System.out.println(g + "\t" + g.day);
//      System.out.println(g + "\t" + g.month);
//      System.out.println(g + "\t" + g.year);
      System.out.println(g + "\t" + g.source);
      System.out.println(g + "\t" + g.lang);
    }
  }
}
