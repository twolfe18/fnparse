#!/usr/bin/env python

# Interpolates between max and expectation in reducing
# pointwise mutual information to (pseudo) mutual information.

import dedup_sim_feats
from dedup_sim_feats import Feature
import collections
import sys, copy
import bisect
import numpy as np
import math

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
  #return (feat.ig_rank_by_key + feat.ig_range) / 2.0
  return feat.ig_max_weight
def ig_exp_weight(feat):
  return feat.prob

def ig_rank_by_count3(feat, count2cdf, min_count=60):
  '''
  OPTIMIZED, requires count2cdf is defaultdict with sorted lists of feat.ig as values.
  Looks at all Features which have a count matching the given
  feature (or a similar count), and returns a score between 0
  (feat has the smallest IG in the list) and 1 (feat has is the max).
  '''
  rnk = 0
  n = 0
  l = r = feat.count
  while n < min_count and l > 0:
    lf = count2cdf[l]
    rnk += bisect.bisect_right(lf, feat.ig)
    n += len(lf)
    if r > l:
      lf = count2cdf[r]
      rnk += bisect.bisect_right(lf, feat.ig)
      n += len(lf)
    l -= 1
    r += 1
  return float(rnk) / float(n)

def ig_rank_by_count2(feat, count2cdf, min_count=60):
  '''
  SLOW, DON'T USE.
  OPTIMIZED, requires count2cdf is defaultdict with values sorted by feat.ig.
  Looks at all Features which have a count matching the given
  feature (or a similar count), and returns a score between 0
  (feat has the smallest IG in the list) and 1 (feat has is the max).
  '''
  def rank(needle, haystack):
    i = 0
    for v in haystack:
      if needle <= v:
        break
      i += 1
    return i
  rnk = 0
  n = 0
  l = r = feat.count
  while n < min_count and l > 0:
    lf = count2cdf[l]
    rnk += rank(feat.ig, (f.ig for f in lf))
    n += len(lf)
    if r > l:
      lf = count2cdf[r]
      rnk += rank(feat.ig, (f.ig for f in lf))
      n += len(lf)
    l -= 1
    r += 1
  return float(rnk) / float(n)

def ig_rank_by_count(feat, count2cdf, min_count=60):
  '''
  SLOW, DON'T USE.
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

def set_ig_max_weight(features, R=0.8, P=0.2):
  '''
  Mutual infomation is an expectation of the form:
    \sum_i w_i pmi(i)
  If you want vanilla MI, then w_i = c_i / \sum_j c_j
    => This is the 'exp' branch of computing modified MIs, not related to this function
  If you want max_i pmi_i, then you want w_i = indicator(pmi(i) == max(pmi))
    => This is the 'max' branch of computing modified MIs, purpose of this function

  The way to motivate max(pmi) is to say that pmi must be high for SOME, not ALL, i.
  I think this is inherently a grey argument, and the extreme of max(pmi) doesn't seem
  too plausible to me. Taking the grey in mind, we can choose various ways to weight
  pmi such that a varying number of them have to be large.

  Put in the back of your mind combining this technique with exp(pmi) methods, as
  they can be combined later. Here we just come up with weights for max before
  combining with exp.

  In general, you can imagine putting all the i in a row, sorted by pmi.
  The hard max would put all the weight on the last/rightmost i.
  What are ways we can generalize this which have this as a limit?
  I propose we use w_i ~ exp(temp * (pmi(i) - midpmi))
  Where midpmi is any robust measure of scale for the distribution. I considered using midpmi=\max_i pmi(i), but this is not very stable, especially considering you will have pmi's based on a single count, which are biased high. I think I'll use midpmi = arithmetic mean of 1st, 2nd, and 3rd quartiles.
  This gives us the param temp to play with, temp=infinity leads to hard max.
  
  We will choose temp such that (in the sorted plot with i on the x-axis),
  sum of the prob mass lower than rank R is equal to P.
  e.g. the 80/20 rule:
  R=0.8
  P=0.2

  Doing this normalization step means that different (pmi(i)-midpmi)
  distributions get different temperatures.
  While I don't find this particularly appealing a priori, what is
  nice is that it means that you don't have to choose a temperature,
  so it is scale invariant.
  You specify the scale with R and P which can be intuitively understood.

  NOTE: I don't find the rank-based transforms of w_i ~ pmi particularly
  appealing, since they seem to loose a lot of information.
  '''

  # Compute the center of the distribution as an average of medians.
  # This has lower variance than the max and is more robust than the mean.
  n = len(features)
  q1 = features[n // 4].ig
  q2 = features[n // 2].ig
  q3 = features[(3*n) // 4].ig
  assert q1 <= q2 and q2 <= q3, "%f, %f, %f" % (q1,q2,q3)
  midpmi = (q1+q2+q3)/3.0

  def set_temp_and_get_mass_at_rank(temp, rank):
    assert temp >= 0  # negative values invert intuition: weight min pmi items highly
    assert rank >= 0 and rank <= 1
    Z = 0.0
    for f in features:
      f.ig_max_weight = math.exp(temp * (f.ig - midpmi))
      Z += f.ig_max_weight
    p = 0.0
    for i, f in enumerate(features):
      f.ig_max_weight /= Z
      if i < rank * len(features):
        p += f.ig_max_weight
    assert p >= 0.0 and p <= 1.00001, "p=%f" % (p,)
    return p

  min_err = 9999.9
  min_temp = 1
  for temp in np.arange(0, 20, 0.5):
    p = set_temp_and_get_mass_at_rank(temp, R)
    err = (p-P)**2
    if err < min_err or temp == 0:
      min_err = err
      min_temp = temp

  set_temp_and_get_mass_at_rank(min_temp, R)


def combine_scores(coef, out_file='/dev/stdout'):
  ''' coef is a dict with entries for 'prob', 'rank', 'both' '''

  # Normalize coefs to sum to 1
  z = sum(coef.values())
  coef = {k: v/z for k,v in coef.iteritems()}

  # Read in features and IG estimates from file
  sys.stderr.write('loading features and IG from stdin...\n')
  groups = collections.defaultdict(list)
  for feat in Feature.from_file('/dev/stdin'):
    # This will produce a list of pairs, e.g. [('frame', 1887), ('role', 22)]
    # The rule is that we group by the N-1 values in the list and aggregate over the last
    feat.restrict = parse_restrict_str(feat.restrict)
    assert len(feat.restrict) > 0

    # Reduce memory usage by freeing str_templates
    # This saves ~10% speed/memory, can pay this for the ability to have strings in output.
    # NOTE: This tradeoff could change for features which have longer str_templates.
    #del feat.str_templates

    group_by = []
    group_by.append(tuple(feat.int_templates))
    if len(feat.restrict) > 1:
      group_by += feat.restrict[:-1]
    group_by = tuple(group_by)
    groups[group_by].append(feat)


  # Compute the CDFs for IG based on count
  sys.stderr.write('computing count2cdf...\n')
  count2cdf = collections.defaultdict(list)
  for k, features in groups.iteritems():
    for feat in features:
      count2cdf[feat.count].append(feat)

  # Sort features by PMI, ASCENDING
  sys.stderr.write('sorting by PMI ASCENDING...\n')
  for k in count2cdf.keys():
    igs = np.asarray([f.ig for f in count2cdf[k]])
    count2cdf[k] = sorted(igs)
  for k in groups.keys():
    groups[k] = sorted(groups[k], key=lambda f: f.ig)

  interval = 25000
  kvn = len(groups)

  sys.stderr.write('computing rank by ig...\n')
  for kvi, (key, values) in enumerate(groups.iteritems()):
    # Compute rank
    n = float(len(groups[key]))
    for i, feat in enumerate(groups[key]):
      feat.ig_rank_by_key = float(i+1) / n
    if kvi % interval == 0:
      sys.stderr.write(" %d/%d" % (kvi, kvn))
  sys.stderr.write('\n')

  sys.stderr.write('computing range...\n')
  for kvi, (key, values) in enumerate(groups.iteritems()):
    # Compute range
    mx = values[-1].ig
    mn = values[0].ig
    assert mn <= mx
    if mn == mx:
      for feat in values:
        feat.ig_range = 1 / len(values)
    else:
      for feat in values:
        feat.ig_range = 1 - (mx - feat.ig) / (mx - mn)
    if kvi % interval == 0:
      sys.stderr.write(" %d/%d" % (kvi, kvn))
  sys.stderr.write('\n')

  sys.stderr.write('computing prob...\n')
  for kvi, (key, values) in enumerate(groups.iteritems()):
    # counts -> probablities
    total_count = 0
    for feat in groups[key]:
      total_count += feat.count
    for feat in groups[key]:
      feat.prob = feat.count / float(total_count)
    if kvi % interval == 0:
      sys.stderr.write(" %d/%d" % (kvi, kvn))
  sys.stderr.write('\n')

  sys.stderr.write('computing rank by count...\n')
  for kvi, (key, values) in enumerate(groups.iteritems()):
    # IG -> ranked IG
    # Assumes sorted by IG ASCENDING
    for feat in groups[key]:
      #feat.ig_rank_by_count = ig_rank_by_count(feat, count2cdf)
      #feat.ig_rank_by_count = ig_rank_by_count2(feat, count2cdf)
      feat.ig_rank_by_count = ig_rank_by_count3(feat, count2cdf)
    if kvi % interval == 0:
      sys.stderr.write(" %d/%d" % (kvi, kvn))
  sys.stderr.write('\n')

  sys.stderr.write('computing max weights by tuning cumProbAtRank=const over exp(temp*regret) distribution...\n')
  prob = 0.3  # P
  rank = 0.8  # R
  for kvi, (key, values) in enumerate(groups.iteritems()):
    set_ig_max_weight(values)
    if kvi % interval == 0:
      sys.stderr.write(" %d/%d" % (kvi, kvn))
  sys.stderr.write('\n')

  sys.stderr.write('writing output...\n')
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
      if feat.restrict:
        new_res_str = ','.join(k + '=' + v for k,v in feat.restrict)
        if dedup_sim_feats.NEW_FEATURE_FILE_FORMAT:
          f.write("\t%s" % (new_res_str,))
        else:
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

