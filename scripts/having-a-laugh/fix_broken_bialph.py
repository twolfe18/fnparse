
import sys
import dedup_sim_feats

# If you have a bialph with broken template names like 'Foo-Foo-Top25',
# this script re-writes these template names correctly, e.g. 'Foo-Top25'.
# Reads from stdin and writes to stdout.

if __name__ == '__main__':
  with open('/dev/stdin', 'r') as f:
    for line in f:
      ar = line.rstrip().split('\t')
      template_name = ar[2]
      ar[2] = dedup_sim_feats.undo_template_name_bug(template_name)
      print '\t'.join(ar)

