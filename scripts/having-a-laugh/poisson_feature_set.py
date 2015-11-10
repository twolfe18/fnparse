
# NOTE: I'm pretty sure this is a bad idea, see Makefile for how to do it better

import sys
from dedup_sim_feats import Feature
from math import pow, exp, factorial

TEMPLATE_NAME_BUG = False

def build_feature_set(feature_file, output_file, num_feats, mean_hx, smooth, debug=True):
  if isinstance(num_feats, str):
    num_feats = int(num_feats)
  if isinstance(mean_hx, str):
    mean_hx = float(mean_hx)
  if isinstance(smooth, str):
    smooth = float(smooth)

  # Choose how many features to take
  K = 11
  pmf = [pow(mean_hx, k) * exp(-mean_hx) / factorial(k) + smooth for k in range(K)]
  Z = sum(pmf)
  pmf = [p/Z for p in pmf]
  counts = [int(num_feats * p + 0.5) for p in pmf]
  if debug:
    #print list(enumerate(counts))
    print counts

  # Bin the features by hx
  bins = [[] for i in pmf]
  for f in Feature.from_file(feature_file, template_name_bug=TEMPLATE_NAME_BUG):
    hx_bin = min(K-1, int(f.hx))
    bins[hx_bin].append(f)

  # Pick out the features
  out = []
  for i, budget in enumerate(counts):
    out += bins[i][:budget]

  with open(output_file, 'w') as of:
    for f in out:
      of.write(f.str_like_input() + '\n')

if __name__ == '__main__':
  build_feature_set(*sys.argv[1:])

