#!/usr/bin/env python

# Takes output of IndexSituations/sit_feats/deprels.txt.gz
# 1) does pruning
# 2) packs the output into the StringIntUuidIndex format

import sys, codecs, itertools, subprocess

class Ent:
  def __init__(self, role_type_head):
    assert len(role_type_head) == 3
    self.role = role_type_head[0]
    self.ner_type = role_type_head[1]
    self.head = role_type_head[2]

  def __eq__(self, a):
    return self.role == a.role and self.ner_type == a.ner_type and self.head == a.head

  def __hash__(self):
    return hash(self.role + '_' + self.ner_type + '_' + self.head)

  def __str__(self):
    return u"(%s %s %s)" % (self.role, self.ner_type, self.head)
  def __repr__(self):
    return str(self)

class Row:
  def __init__(self, line):
    ar = line.rstrip().split(u'\t')
    self.deprel = ar[0]
    self.arg0 = Ent(ar[1:4])
    self.arg1 = Ent(ar[4:7])
    self.tok_uuid = ar[7]
    self.comm_uuid = ar[8]
    self.comm_id = ar[9]
    self.line = line
    #print '[Row init]', self.__dict__

def is_gz(filename):
  return filename.lower().endswith('gz')

def prune_rel(input_file, output_file, min_ent_pairs=4):
  ''' expects input_file is sorted by deprel '''
  if is_gz(input_file) or is_gz(output_file):
    raise Exception('only handles txt files')
  print '[prune_rel] input', input_file
  print '[prune_rel] output', output_file
  with codecs.open(input_file, 'r', 'utf-8') as f:
    with codecs.open(output_file, 'w', 'utf-8') as out:
      rows = map(Row, f)
      by_rel = itertools.groupby(rows, lambda r: r.deprel)
      for deprel, group in by_rel:
        group = list(group)
        ent_pairs = set([(r.arg0, r.arg1) for r in group])
        if len(ent_pairs) >= min_ent_pairs:
          #print 'keeping', deprel
          for r in group:
            out.write(r.line)

def prune_ent(input_file, output_file, min_rels=1):
  ''' expects input_file is sorted by arguments '''
  if is_gz(input_file) or is_gz(output_file):
    raise Exception('only handles txt files')
  print '[prune_ent] input', input_file
  print '[prune_ent] output', output_file
  with codecs.open(input_file, 'r', 'utf-8') as f:
    with codecs.open(output_file, 'w', 'utf-8') as out:
      rows = map(Row, f)
      by_arg_pair = itertools.groupby(rows, lambda r: (r.arg0, r.arg1))
      for arg_pair, group in by_arg_pair:
        group = list(group)
        rels = set(r.deprel for r in group)
        if len(rels) >= min_rels:
          #print 'keeping', arg_pair
          for r in group:
            out.write(r.line)

if __name__ == '__main__':
  if len(sys.argv) != 4:
    sys.stderr.write('please provide:')
    sys.exit(1)

  _, command, input_file, output_file = sys.argv
  l = locals()
  if command not in l:
    sys.stderr.write('unknown command: ' + command)
    sys.exit(2)
  l[command](input_file, output_file)
  

