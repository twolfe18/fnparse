
import sys, os, collections

class IG:
  def __init__(self):
    # values are (numInstancesOfIG, sumIG)
    self.int2ig = collections.defaultdict(lambda: (0, 0.0))

  def update(self, ig_file):
    with open(ig_file, 'r') as f:
      for line in f:
        try:
          (ig, template) = line.strip().split('\t')
          ig = float(ig)
          template = int(template)
          n, sum_ig = self.int2ig[template]
          self.int2ig[template] = (n + 1, sum_ig + ig)
        except:
          print line, ig_file


  def get_ig(self, template_int):
    n, sum_ig = self.int2ig[template_int]
    if n == 0:
      return 0.0
    else:
      return sum_ig / n

  def get_templates_sorted_by_ig_decreasing(self):
    return sorted(self.int2ig.keys(), key=lambda t: self.get_ig(t), reverse=True)

if __name__ == '__main__':
  ig = IG()
  for f in sys.argv[1:]:
    sys.stderr.write("adding %s\n" % (f))
    ig.update(f)
  templates = ig.get_templates_sorted_by_ig_decreasing()
  for t in templates:
    print "%.5f\t%d" % (ig.get_ig(t), t)

