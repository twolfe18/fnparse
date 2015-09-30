
FN_WD = /export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a
PB_WD = /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b

framenet-ig-feat.txt:
	ssh test1b "cat $(FN_WD)/ig/products/ig-files/shard-* | sort -rg" >framenet-ig-feat.txt

framenet-%.fs: framenet-ig-feat.txt
	python dedup-features-based-on-template-names.py framenet-ig-feat.txt $* framenet-$*.fs

propbank-ig-feat.txt:
	ssh test1b "cat $(PB_WD)/ig/products/ig-files/shard-* | sort -rg" >propbank-ig-feat.txt

propbank-%.fs: propbank-ig-feat.txt
	python dedup-features-based-on-template-names.py propbank-ig-feat.txt $* propbank-$*.fs

all: \
	propbank-10.fs propbank-20.fs propbank-40.fs propbank-80.fs propbank-160.fs propbank-320.fs propbank-640.fs \
	framenet-10.fs framenet-20.fs framenet-40.fs framenet-80.fs framenet-160.fs framenet-320.fs framenet-640.fs

# NOTE: Here is how you can tell the difference between two feature sets (e.g. if you have new IG estimates):
# DIM=10
# diff <(grep -oP '\[.+\]' propbank-${DIM}.fs | sort) <(grep -oP '\[.+\]' old/propbank-${DIM}.fs | sort)

