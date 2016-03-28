#!/usr/bin/env python

# Interpolates between max and expectation in reducing
# pointwise mutual information to (pseudo) mutual information.

from dedup_sim_feats import Feature
import collections
import sys, copy

def harmonic_mean(a, b):
  if a + b <= 0:
    return 0
  return 2.0 * a * b / (a + b)

def parse_restrict_str(string):
  # r = string.split(',')
  # r = [x.split('=')[-1] for x in r]
  # r = [int(x) for x in r]
  # r = tuple(r)
  return [tuple(x.split('=')) for x in string.split(',')]

class Avg:
  def __init__(self):
    self.wx = 0.0
    self.W = 0.0
    self.n = 0

  def add(self, weight, value):
    self.wx += weight * value
    self.W += weight
    self.n += 1

  def avg(self):
    assert self.n > 0
    return self.wx / self.W

def ig_max_weight(feat):
  return (feat.ig_rank_by_key + feat.ig_range) / 2.0
def ig_exp_weight(feat):
  return feat.prob

def ig_rank_by_count(feat, count2cdf, min_count=60):
  '''
  Looks at all Features which have a count matching the given
  feature (or a similar count), and returns a score between 0
  (feat has the smallest IG in the list) and 1 (feat has is the max).
  '''
  cdf = []
  l = r = feat.count
  while len(cdf) < min_count and l > 0:
    cdf += count2cdf[l]
    if r > l:
      cdf += count2cdf[r]
    l -= 1
    r += 1
  cdf = sorted(cdf, key=lambda feat: feat.ig)
  r = 0
  for f in cdf:
    if feat.ig > f.ig:
      r += 1
    else:
      break
  return float(r) / len(cdf)

def combine_scores(coef, out_file='/dev/stdout'):
  ''' coef is a dict with entries for 'prob', 'rank', 'both' '''

  # Normalize coefs to sum to 1
  z = sum(coef.values())
  coef = {k: v/z for k,v in coef.iteritems()}

  # Read in features and IG estimates from file
  groups = collections.defaultdict(list)
  for feat in Feature.from_file('/dev/stdin'):
    # This will produce a list of pairs, e.g. [('frame', 1887), ('role', 22)]
    # The rule is that we group by the N-1 values in the list and aggregate over the last
    feat.restrict = parse_restrict_str(feat.restrict)
    assert len(feat.restrict) > 0

    group_by = []
    group_by.append(tuple(feat.int_templates))
    if len(feat.restrict) > 1:
      group_by += feat.restrict[:-1]
    group_by = tuple(group_by)
    groups[group_by].append(feat)


  # Compute the CDFs for IG based on count
  count2cdf = collections.defaultdict(list)
  for k, features in groups.iteritems():
    for feat in features:
      count2cdf[feat.count].append(feat)
  #count2cdf = {k:sorted(v, lambda feat: feat.count) for k,v in count2cdf.iteritems()}


  for key, values in groups.iteritems():
    # Sort by PMI
    groups[key] = sorted(values, key=lambda feat: feat.ig, reverse=True)

    # Compute rank
    n = float(len(groups[key]))
    for i, feat in enumerate(groups[key]):
      feat.ig_rank_by_key = (n-i) / n

    # Compute range
    mx = max(f.ig for f in values)
    mn = min(f.ig for f in values)
    if mn == mx:
      for feat in values:
        feat.ig_range = 1 / len(values)
    else:
      for feat in values:
        feat.ig_range = 1 - (mx - feat.ig) / (mx - mn)

    # counts -> probablities
    total_count = 0
    for feat in groups[key]:
      total_count += feat.count
    for feat in groups[key]:
      feat.prob = feat.count / float(total_count)

    # IG -> ranked IG
    for feat in groups[key]:
      feat.ig_rank_by_count = ig_rank_by_count(feat, count2cdf)

  #output = []   # list of (template, frame, score)
  with open(out_file, 'w') as f:
    for key, features in groups.iteritems():
      ig_abs = Avg()
      ig_rbc = Avg()
      hx = Avg()
      sel = Avg()
      count_sum = 0
      for feat in features:
        w = 0.0
        w += coef['exp'] * ig_exp_weight(feat)
        w += coef['max'] * ig_max_weight(feat)
        w += coef['both'] * harmonic_mean(ig_exp_weight(feat), ig_max_weight(feat))
        ig_abs.add(w, feat.ig)
        ig_rbc.add(w, feat.ig_rank_by_count)
        hx.add(feat.count, feat.hx)
        sel.add(feat.count, feat.selectivity)
        count_sum += feat.count

      ig = 0
      ig += coef['ig_abs'] * ig_abs.avg()
      ig += coef['ig_rbc'] * ig_rbc.avg()
      ig /= coef['ig_rbc'] + coef['ig_abs']

      # Average/output feature
      feat = copy.copy(features[0])
      feat.rank = -1  # no longer relevant
      feat.score = 0
      feat.ig = ig
      feat.hx = hx.avg()
      feat.selectivity = sel.avg()
      feat.count = count_sum
      feat.restrict = key[1:]
      f.write(feat.str_like_input())
      new_res_str = ','.join(k + '=' + v for k,v in feat.restrict)
      f.write("\t%d\t%s" % (count_sum, new_res_str))
      f.write('\n')


if __name__ == '__main__':
  '''
  exp/max/both is a way of changing w in:
    sum_i w_i g_i
  and most methods here focus on choosing w_i intelligently.
  exp, max, and both are different ways of choosing w_i.

  we can also change g_i by a rank transform on comparable scores:
    sum_i w_i range(g_i, all g s.t. count == w_i.count)
  (we call this 'rank by count' or 'rbc')
  '''
  coef = {
    'exp': float(sys.argv[1]),
    'max': float(sys.argv[2]),
    'both': float(sys.argv[3]),
    'ig_abs': float(sys.argv[4]),
    'ig_rbc': float(sys.argv[5]),
    'verbose': False,
  }
  combine_scores(coef)

