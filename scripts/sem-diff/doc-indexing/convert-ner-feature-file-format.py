#!/usr/bin/env python

# Pipe in "<termId><tab><nerType><tab><uuid>" terms sorted by (nertype, termId)
# Provide an output directory where nerFeats.<nerType>.txt files will be written
# Output format is "<termId>(<tab><uuid>)+"

import sys, os, itertools

if len(sys.argv) != 2:
  print 'please provide an output directory'
  sys.exit(-1)

out = sys.argv[1]
if not os.path.isdir(out):
  os.path.mkdirs(out)

class Row:
  def __init__(self, line):
    ar = line.rstrip().split('\t')
    assert len(ar) == 4
    self.term = int(ar[0])
    self.ner_type = ar[1]
    self.comm_uuid = ar[2]
    self.tok_uuid = ar[3]

open_files = {}

with open('/dev/stdin', 'r') as f:
  rows = itertools.imap(Row, f)
  for (term, ner_type), docs in itertools.groupby(rows, lambda r: (r.term, r.ner_type)):
    f = os.path.join(out, "nerFeats.%s.txt" % ner_type)
    if f not in open_files:
      print 'opening', f
      open_files[f] = open(f, 'w')
    fd = open_files[f]

    u = set()
    #fd.write("%d\t%s" % (term, ner_type))
    fd.write("%d" % term)
    for r in docs:
      #fd.write("\t%s" % r.comm_uuid)
      if r.tok_uuid not in u:
        u.add(r.tok_uuid)
        fd.write("\t%s" % r.tok_uuid)
    fd.write('\n')

for f, fd in open_files.items():
  print 'closing', f
  fd.close()

