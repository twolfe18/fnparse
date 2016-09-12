package edu.jhu.hlt.ikbp.features;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.ikbp.EcbPlusUtil;
import edu.jhu.hlt.ikbp.EcbPlusXmlStore;
import edu.jhu.hlt.ikbp.EcbPlusXmlWrapper;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.tutils.ConllxToDocument;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 *
 *
 * @author travis
 */
public class EcbPlusMentionFeatureExtractor implements MentionFeatureExtractor {
  
  private ConllxToDocument c2d;
  private File topicParentConll;
  private EcbPlusXmlStore xmlDocs;
  private Map<String, Document> documents;
  private Map<String, int[]> m_id2t_id;
  
  public boolean debug = true;
  
  /**
   * @param topicParentConll provides POS and dependency parses (e.g. Parsey McParseface)
   * @param topicParentXml provides mention locations
   */
  public EcbPlusMentionFeatureExtractor(File topicParentConll, EcbPlusXmlStore xmlDocs) {
    if (!topicParentConll.isDirectory())
      throw new IllegalArgumentException("not a dir: " + topicParentConll.getPath());
    c2d = new ConllxToDocument(new MultiAlphabet());
    documents = new HashMap<>();
    m_id2t_id = new HashMap<>();
    this.topicParentConll = topicParentConll;
    this.xmlDocs = xmlDocs;
  }
  
  public Document getDocument(String docId) {
    Document doc = documents.get(docId);
    if (doc == null) {
      String topic = EcbPlusUtil.getTopic(docId);
      File f = new File(new File(topicParentConll, topic), docId + ".conll");
      doc = c2d.parseSafe(docId, f);
      Object old = documents.put(docId, doc);
      assert old == null : "two keys: " + docId;
      
      EcbPlusXmlWrapper xml = xmlDocs.get(docId);
      for (EcbPlusXmlWrapper.Node n : xml.getNodes()) {
        old = m_id2t_id.put(n.m_id, n.t_id);
        assert old == null;
      }
    }
    return doc;
  }

  @Override
  public void extract(String m_id, List<Id> addTo) {
    String docId = EcbPlusUtil.getDocumentId(m_id);
    Document doc = getDocument(docId);
    MultiAlphabet a = doc.getAlphabet();
    int[] toks = m_id2t_id.get(m_id);
    LabeledDirectedGraph g = doc.parseyMcParseFace;
    int h = headToken(toks, g);
    
    if (debug) {
      for (int i = 0; i < toks.length; i++) {
        int w = doc.getWord(toks[i]);
        String ws = a.word(w);
        int p = doc.getPosH(toks[i]);
        String ps = a.pos(p);

        LabeledDirectedGraph.Node n = g.getNode(toks[i]);
        String hs;
        String edge;
        if (n.numParents() == 1) {
          int hh = n.getParent(0);
          hh = doc.getWord(hh);
          hs = a.word(hh);
          edge = a.dep(n.getParentEdgeLabel(0));
        } else {
          assert n.numParents() == 0;
          hs = "ROOT";
          edge = "root";
        }

        System.out.println(m_id + "\t" + toks[i] + "\t" + ws + "\t" + ps + "\t" + edge + "\t" + hs);
     }
      System.out.println();
    }
    
    // head word
    int w = doc.getWord(h);
    String ws = a.word(w);
    f(FeatureType.HEADWORD, ws, addTo);
    
    // head pos
    int p = doc.getPosH(h);
    String ps = a.pos(p);
    f(FeatureType.REGULAR, "head.pos=" + ps, addTo);
    
    // parent word and dep edge
    LabeledDirectedGraph.Node hn = g.getNode(h);
    if (hn.numParents() == 0) {
      f(FeatureType.REGULAR, "parent=ROOT", addTo);
    } else {
      assert hn.numParents() == 1;
      LabeledDirectedGraph.Node hpn = hn.getParentNode(0);
      String hpw = doc.getWordStr(hpn.getNodeIndex());
      String hpd = a.dep(hn.getParentEdgeLabel(0));
      f(FeatureType.REGULAR, "parent=" + hpw, addTo);
      f(FeatureType.REGULAR, "parent." + hpd + "=" + hpw, addTo);
    }
    
    for (int i = 0; i < hn.numChildren(); i++) {
      LabeledDirectedGraph.Node hcn = hn.getChildNode(i);
      String hcw = doc.getWordStr(hcn.getNodeIndex());
      String hcd = a.dep(hn.getChildEdgeLabel(i));
      f(FeatureType.REGULAR, "child=" + hcw, addTo);
      f(FeatureType.REGULAR, "child." + hcd + "=" + hcw, addTo);
    }
  }
  
  public static int headToken(int[] tokens, LabeledDirectedGraph deps) {
    int head = -1;
    int headDepth = -1;
    for (int i = tokens.length - 1; i >= 0; i--) {
      LabeledDirectedGraph.Node n = deps.getNode(tokens[i]);
      int d = n.computeDepthAssumingTree();
      if (headDepth < 0 || d < headDepth) {
        head = tokens[i];
        headDepth = d;
      }
    }
    return head;
  }
  
  public static void f(FeatureType t, String name, List<Id> addTo) {
    Id i = new Id();
    i.setType(t.ordinal());
    i.setName(name);
    i.setId(Hash.hash(name));
    addTo.add(i);
  }

}
