package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.FileUtil;

public class VwLine implements Serializable {
  private static final long serialVersionUID = 3475667650409936816L;

  public static class Namespace implements Serializable {
    private static final long serialVersionUID = -4469507818509671225L;

    public final char name;
    public List<String> features;
    
    public Namespace(char name) {
      this.name = name;
      this.features = new ArrayList<>();
    }
  }
  
  int y;
  List<Namespace> x;
  
  public VwLine(String line) {
    String[] ar = line.split("\\s+");
    y = Integer.parseInt(ar[0]);
    x = new ArrayList<>();
    Namespace cur = newNs(ar[1]);
    for (int i = 2; i < ar.length; i++) {
      if (ar[i].charAt(0) == '|') {
        cur = newNs(ar[i]);
      } else {
        cur.features.add(ar[i]);
      }
    }
  }
  
  public Namespace remove(char namespace) {
    int n = x.size();
    for (int i = 0; i < n; i++)
      if (namespace == x.get(i).name)
        return x.remove(i);
    return null;
  }
  
  /** does feature = nameSpace + "/" + feat */
  public void extractAllFeatures(List<String> addTo) {
    for (Namespace ns : x)
      for (String feat : ns.features)
        addTo.add(ns.name + "/" + feat);
  }
  
  public void pruneByNamespace(Set<String> nsKeep) {
    List<Namespace> keep = new ArrayList<>();
    for (Namespace ns : x)
      if (nsKeep.contains(ns.name + ""))
        keep.add(ns);
    x = keep;
  }
  
  private Namespace newNs(String nsToken) {
    Namespace ns = new Namespace(nsToken.charAt(1));    // charAt(0) == '|'
    x.add(ns);
    return ns;
  }
  
  public void show() {
    System.out.println("(VwLine: y=" + y);
    for (Namespace ns : x)
      System.out.println(ns.name + ": " + ns.features);
    System.out.println(")");
  }
  
  public static void main(String[] args) throws Exception {
    VwLine l = new VwLine("1 |a sd=1 od=7 pd=1 ps=Sparks ps=VBZ ne=1 |A sc=nsubj(Sparks,Nicholas) sc=nsubj(VBZ,NNP) sc=dobj(Sparks,book) sc=dobj(VBZ,NN) sc=prep(Sparks,in) sc=prep(VBZ,IN) |d dp=6 dp4=n dp3=n dp2=n dp1=n dir=l |m The book A Bend in their three weeks a planned unk4-JJ spark job I enjoy What 's New condition terms of the ghost , unk4-NNP |M w=10 e=1 |O amod(was,SOURCE) n=2 |p prep(SOURCE,in) pobj(in,job) rcmod(job,enjoy) ccomp(enjoy,spark) dobj(spark,institution) amod(institution,TARGET) |P prep(VBZ,IN) pobj(IN,NN) rcmod(NN,VBP) ccomp(VBP,VB) dobj(VB,NN) amod(NN,NNP) |q offpath*(h,m)-prep(SOURCE,in) prep(SOURCE,in)-pobj(in,job) pobj(in,job)-rcmod(job,enjoy) rcmod(job,enjoy)-ccomp(enjoy,spark) ccomp(enjoy,spark)-dobj(spark,institution) dobj(spark,institution)-amod(institution,TARGET) amod(institution,TARGET)-offpath(h,m) |Q offpath*(h,m)-prep(VBZ,IN) prep(VBZ,IN)-pobj(IN,NN) pobj(IN,NN)-rcmod(NN,VBP) rcmod(NN,VBP)-ccomp(VBP,VB) ccomp(VBP,VB)-dobj(VB,NN) dobj(VB,NN)-amod(NN,NNP) amod(NN,NNP)-offpath(h,m) |s wikidata.org/entity/Q36180 dbpedia.org/ontology/Person wikidata.org/entity/Q5 xmlns.com/foaf/0.1/Person w3.org/2002/07/owl#Thing schema.org/Person wikidata.org/entity/Q215627 ontologydesignpatterns.org/ont/dul/DUL.owl#NaturalPerson dbpedia.org/ontology/Agent ontologydesignpatterns.org/ont/dul/DUL.owl#Agent |S det(SOURCE,0.0) ROOT(ROOT,SOURCE) dobj(SOURCE,I) prep(SOURCE,this) prep(SOURCE,Rodanthe) n=5 |t offpath*(h,m)-offpath*(h,m)-prep(SOURCE,in) offpath*(h,m)-prep(SOURCE,in)-pobj(in,job) prep(SOURCE,in)-pobj(in,job)-rcmod(job,enjoy) pobj(in,job)-rcmod(job,enjoy)-ccomp(enjoy,spark) rcmod(job,enjoy)-ccomp(enjoy,spark)-dobj(spark,institution) ccomp(enjoy,spark)-dobj(spark,institution)-amod(institution,TARGET) dobj(spark,institution)-amod(institution,TARGET)-offpath(h,m) amod(institution,TARGET)-offpath(h,m)-offpath(h,m) |T offpath*(h,m)-offpath*(h,m)-prep(VBZ,IN) offpath*(h,m)-prep(VBZ,IN)-pobj(IN,NN) prep(VBZ,IN)-pobj(IN,NN)-rcmod(NN,VBP) pobj(IN,NN)-rcmod(NN,VBP)-ccomp(VBP,VB) rcmod(NN,VBP)-ccomp(VBP,VB)-dobj(VB,NN) ccomp(VBP,VB)-dobj(VB,NN)-amod(NN,NNP) dobj(VB,NN)-amod(NN,NNP)-offpath(h,m) amod(NN,NNP)-offpath(h,m)-offpath(h,m) |w /m/0328bl /m/0328bl/s=2483 /m/0328bl/s=2483/m=0-2");
//    System.out.println(l);
    l.show();
    
    File f = new File("/tmp/neg.vw");
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        VwLine vw = new VwLine(line);
//        vw.show();
      }
    }
  }
}