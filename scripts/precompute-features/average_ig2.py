
import sys, os, collections

class IG:
  def __init__(self):
    # values are (numInstancesOfIG, sumIG)
    self.int2ig = collections.defaultdict(lambda: (0, 0.0))
    self.t2n_hx_hyx_hy = collections.defaultdict(lambda: (0, 0.0, 0.0, 0.0))

  def update(self, ig_file):
    with open(ig_file, 'r') as f:
      for line in f:
        try:
          #(ig, template) = line.strip().split('\t')
          #ig = float(ig)
          #template = int(template)

          ar = line.strip().split('\t')
          template = int(ar[0])
          ig = float(ar[1])
          hx = float(ar[2])
          hyx = float(ar[3])
          hy = float(ar[4])

          n, sum_ig = self.int2ig[template]
          self.int2ig[template] = (n + 1, sum_ig + ig)

          n, sum_hx, sum_hyx, sum_hy = self.t2n_hx_hyx_hy[template]
          self.t2n_hx_hyx_hy[template] = (n + 1, sum_hx + hx, sum_hyx + hyx, sum_hy + hy)

        except:
          print line, ig_file


  def get_ig(self, template_int):
    #n, sum_ig = self.int2ig[template_int]
    #if n == 0:
    #  return 0.0
    #else:
    #  return sum_ig / n
    n, sum_hx, sum_hyx, sum_hy = self.t2n_hx_hyx_hy[template_int]
    if n == 0:
      return 0
    hx = sum_hx / n
    hyx = sum_hyx / n
    hy = sum_hy / n
    return hx + hy - hyx

  def get_hx(self, template_int):
    n, sum_hx, sum_hyx, sum_hy = self.t2n_hx_hyx_hy[template_int]
    if n == 0:
      raise Exception('no observations')
    return sum_hx / n

  def get_hyx(self, template_int):
    n, sum_hx, sum_hyx, sum_hy = self.t2n_hx_hyx_hy[template_int]
    if n == 0:
      raise Exception('no observations')
    return sum_hyx / n

  def get_hy(self, template_int):
    n, sum_hx, sum_hyx, sum_hy = self.t2n_hx_hyx_hy[template_int]
    if n == 0:
      raise Exception('no observations')
    return sum_hy / n

  def get_templates_sorted_by_ig_decreasing(self):
    return sorted(self.int2ig.keys(), key=lambda t: self.get_ig(t), reverse=True)

if __name__ == '__main__':
  if len(sys.argv) < 3:
    print 'please provide:'
    print '1) an output file for average -- same format as input'
    print '2+) input information gain files'
    sys.exit(1)

  print 'writing to', sys.argv[1]
  ig = IG()
  for f in sys.argv[2:]:
    sys.stderr.write("adding %s\n" % (f))
    ig.update(f)
  templates = ig.get_templates_sorted_by_ig_decreasing()
  with open(sys.argv[1], 'w') as outf:
    for t in templates:
      s = '\t'.join(map(str, [t, ig.get_ig(t), ig.get_hx(t), ig.get_hyx(t), ig.get_hy(t)]))
      outf.write(s + '\n')

