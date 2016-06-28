#!/usr/bin/env python

# TODO Currently supports just words and POS tags

# CoNLL document format reader for dependency annotated corpora.
# The expected format is described e.g. at http://ilk.uvt.nl/conll/#dataformat
#
# Data should adhere to the following rules:
#   - Data files contain sentences separated by a blank line.
#   - A sentence consists of one or tokens, each one starting on a new line.
#   - A token consists of ten fields described in the table below.
#   - Fields are separated by a single tab character.
#   - All data files will contains these ten fields, although only the ID
#     column is required to contain non-dummy (i.e. non-underscore) values.
# Data files should be UTF-8 encoded (Unicode).
#
# Fields:
# 1  ID:      Token counter, starting at 1 for each new sentence and increasing
#             by 1 for every new token.
# 2  FORM:    Word form or punctuation symbol.
# 3  LEMMA:   Lemma or stem.
# 4  CPOSTAG: Coarse-grained part-of-speech tag or category.
# 5  POSTAG:  Fine-grained part-of-speech tag. Note that the same POS tag
#             cannot appear with multiple coarse-grained POS tags.
# 6  FEATS:   Unordered set of syntactic and/or morphological features.
# 7  HEAD:    Head of the current token, which is either a value of ID or '0'.
# 8  DEPREL:  Dependency relation to the HEAD.
# 9  PHEAD:   Projective head of current token.
# 10 PDEPREL: Dependency relation to the PHEAD.
#
# This CoNLL reader is compatible with the CoNLL-U format described at
#   http://universaldependencies.org/format.html
# Note that this reader skips CoNLL-U multiword tokens and ignores the last two
# fields of every line, which are PHEAD and PDEPREL in CoNLL format, but are
# replaced by DEPS and MISC in CoNLL-U.

import sys, codecs, collections

def new_token():
  return collections.defaultdict(lambda: '_')

input_file = sys.argv[1]
output_file = sys.argv[2]
with codecs.open(input_file, 'r', 'utf-8') as r:
  with codecs.open(output_file, 'w', 'utf-8') as w:
    t = ['ID', 'FORM', 'LEMMA', 'CPOSTAG', 'POSTAG', 'FEATS', 'HEAD', 'DEPREL', 'PHEAD', 'PDEPREL']
    a = collections.defaultdict(new_token)
    ids = []
    for line in r:
      if line.startswith('startdoc'):
        for ti in ids:
          w.write(u'\t'.join([a[ti][k] for k in t]))
          w.write(u'\n')
        if ids:
          w.write(u'\n')
        a.clear()
        ids = []
        continue

      toks = line.rstrip().split(u' ')
      #print toks
      command = toks[1]
      if command == u'word2':
        ti = str(int(toks[2]) + 1)
        a[ti]['ID'] = ti
        a[ti]['FORM'] = toks[3]
        ids.append(ti)
      elif command == u'pos2':
        ti = str(int(toks[2]) + 1)
        a[ti]['POSTAG'] = toks[3]

    for ti in ids:
      w.write(u'\t'.join([a[ti][k] for k in t]))
      w.write(u'\n')
    if ids:
      w.write(u'\n')


