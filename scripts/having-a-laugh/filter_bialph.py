
# Bialphs can be large and take a long time to read in.
# This script takes a bialph and some feature set files
# then filters the bialph to only contain templates that
# are in at least one feature set.

# NOTE: If you need to use this again, it might be worth using
# a byte array (https://docs.python.org/2/library/array.html#module-array)
# over a set.
# Propbank has about 425M features, Framenet is less than half that.
# Propbank is taking over an hour or so to perform the filtering.

import sys, codecs
from dedup_sim_feats import Feature

def get_templates(feature_file_list):
  ''' returns a set of (int) templates '''
  templates = set()
  for ff in feature_file_list:
    print 'getting templates from', ff
    n = len(templates)
    for f in Feature.from_file(ff):
      for t in f.int_templates:
        templates.add(t)
    t = len(templates)
    print 'read', (t-n), 'templates, up to', t, 'total'
  return templates

def filter_bialph(input_bialph_file, output_bialph_file, int_template_set, print_interval=250000):
  inl = 0
  outl = 0
  with codecs.open(output_bialph_file, 'w', 'utf-8') as bo:
    with codecs.open(input_bialph_file, 'r', 'utf-8') as bi:
      for line in bi:
        inl += 1
        ar = line.rstrip().split('\t')
        template = int(ar[0])
        if template in int_template_set:
          outl += 1
          bo.write(line)
        if inl % print_interval == 0:
          sys.stdout.write(" %d(%d)" % (inl, outl))
  print

if __name__ == '__main__':
  if len(sys.argv) < 4:
    print 'please provide:'
    print '1) an input bialph file (4 or 6 col tsv, as long as first col is int template)'
    print '2) an output bialph'
    print '3+) feature files containing the templates you would like to keep'
    sys.exit(-1)
  ib = sys.argv[1]
  ob = sys.argv[2]
  ffs = sys.argv[3:]
  templates = get_templates(ffs)
  filter_bialph(ib, ob, templates)

