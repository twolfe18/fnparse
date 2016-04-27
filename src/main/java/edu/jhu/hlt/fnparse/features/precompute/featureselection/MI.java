package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.io.Serializable;

public class MI implements Serializable {

  public static class Fixed extends MI {
    private static final long serialVersionUID = 6352049409141433353L;
    public double mi_set;
    public Fixed(double mi_set) {
      super();
      this.mi_set = mi_set;
    }
    @Override
    public double mi() {
      return mi_set;
    }
  }

  /** Values derived from counts in {@link TemplateIG} */
  public static class Summary implements Serializable {
    private static final long serialVersionUID = 3888066629569424209L;

    // What is being described
    public int templateInt;
    public String templateName;
    // Values of interest

    public MI miEmpirical = null;
    public MI miSmoothed = null;
    public double h_yx;
    public double h_y_p, h_y, h_y_emp;
    public double h_x_p, h_x, h_x_emp;

    // Fraction of the time this feature fired
    public double selectivity;

    // Providence
    public double alpha_yx_p;
    public double alpha_y_p;
    public double alpha_x_p;
    public double alpha_y;
    public double alpha_x;
    public double C;    // sum of counts
    public int numInstances;
    public int hashinigDim;

    public double mi() {
      if ((miEmpirical == null) == (miSmoothed == null))
        throw new RuntimeException("empNull=" + (miEmpirical==null) + " smoNull=" + (miSmoothed==null));
      if (miEmpirical != null)
        return miEmpirical.mi();
      return miSmoothed.mi();
    }

    @Override
    public String toString() {
      return "(MISummary"
          //          + " templateInt=" + templateInt
          //          + " templateName=" + templateName
          + " miEmpirical=" + miEmpirical
          + " miSmoothed=" + miSmoothed
          + " h_y_p=" + h_y_p
          + " h_y=" + h_y
          + " h_y_emp=" + h_y_emp
          + " h_x_p=" + h_x_p
          + " h_x=" + h_x
          + " h_x_emp=" + h_x_emp
          + " alpha_yx_p=" + alpha_yx_p
          + " alpha_y_p=" + alpha_y_p
          + " alpha_x_p=" + alpha_x_p
          + " alpha_y=" + alpha_y
          + " alpha_x=" + alpha_x
          + " C=" + C
          + " numInstances=" + numInstances
          + " hashinigDim=" + hashinigDim
          + ")";
    }
  }

  private static final long serialVersionUID = -5839206491069959192L;

  private double mi_zero, mi_nonzero, mi_zero_correction;

  public void updateMiZero(double x) {
    assert Double.isFinite(x) && !Double.isNaN(x);
    mi_zero += x;
  }

  public void updateMiNonzero(double p_yx, double pmi_numerator, double pmi_denominator) {
    if (p_yx > 0)
      mi_nonzero += p_yx * (Math.log(pmi_numerator) - Math.log(pmi_denominator));
    else
      assert p_yx == 0;
  }

  public void updateMiZeroCorrection(double p_yx, double pmi_numerator, double pmi_denominator) {
    if (p_yx > 0)
      mi_zero_correction += p_yx * (Math.log(pmi_numerator) - Math.log(pmi_denominator));
    else
      assert p_yx == 0;
  }

  public double mi() {
    return (mi_zero - mi_zero_correction) + mi_nonzero;
  }

  @Override
  public String toString() {
    return String.format("%.4f [=(%.4f - %.4f) + %.4f]",
        mi(), mi_zero, mi_zero_correction, mi_nonzero);
  }
}