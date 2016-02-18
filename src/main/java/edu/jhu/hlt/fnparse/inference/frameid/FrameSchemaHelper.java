package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.BufferedReader;
import java.io.File;
import java.io.Serializable;
import java.util.Arrays;

import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

public class FrameSchemaHelper implements Serializable {
  private static final long serialVersionUID = -1402568544812951741L;

  public enum Schema {
    FRAMENET,
    PROPBANK,
    OTHER,
  }

  private int nullFrameId;
  private int numFrames;
  private int[] numFramesBySchema;
  private Schema[] frame2schema;  // indexed by frame id, values are "framenet", "propbank", or "other"

  /**
   * @param roleNames a file like raw-shards/job-0-of-256/role-names.txt.gz which
   * contains the role/frame names in a feature file produced by {@link FeaturePrecomputation}.
   */
  public FrameSchemaHelper(File roleNames) {
    if (!roleNames.isFile())
      throw new IllegalArgumentException("not a roleNames file: " + roleNames.getPath());
    Log.info("loading role names from " + roleNames.getPath());
    nullFrameId = -1;
    numFrames = 1;  // this counts nullFrame
    frame2schema = new Schema[12000];
    numFramesBySchema = new int[Schema.values().length];
    Arrays.fill(frame2schema, Schema.OTHER);
    try (BufferedReader r = FileUtil.getReader(roleNames)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\\t");
        int frameId = Integer.parseInt(toks[0]);
        if (frameId < 0)
          continue;
        String roleName = toks[1];
        if (roleName.equals("f=UKN")) {
          nullFrameId = frameId;
        } else if (roleName.indexOf("f=framenet/") == 0) {
          frame2schema[frameId] = Schema.FRAMENET;
          numFrames++;
          numFramesBySchema[Schema.FRAMENET.ordinal()]++;
        } else if (roleName.indexOf("f=propbank/") == 0) {
          frame2schema[frameId] = Schema.PROPBANK;
          numFrames++;
          numFramesBySchema[Schema.PROPBANK.ordinal()]++;
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assert nullFrameId >= 0;
    Log.info("done, nullFrameId=" + nullFrameId + " numFrames=" + numFrames
        + " numFramesBySchema=" + Arrays.toString(numFramesBySchema));
  }

  public int nullFrameId() {
    return nullFrameId;
  }

  public int numFrames() {
    return numFrames;
  }

  public int numFrames(Schema s) {
    return numFramesBySchema[s.ordinal()];
  }

  public Schema getSchema(int y) {
    return frame2schema[y];
  }

//  public boolean relevant(int y1, int y2) {
//    return frame2schema[y1] == frame2schema[y2];
//  }

}
