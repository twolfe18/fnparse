# <Feat rank=1 n=3 ig=0.3990 ['CfgFeat-CommonParent-Category', 'head1RootPathNgram-LEMMA-DEP-len4', 'head1head2Path-POS-DEP-t']>
# <Feat rank=5 n=2 ig=0.3950 ['head1RootPathNgram-LEMMA-DEP-len4', 'head1head2Path-POS-DEP-t']>
# <Feat rank=7 n=3 ig=0.3928 ['head1RootPathNgram-NONE-DEP-len4', 'head1head2Path-NONE-DEP-t', 'head1head2Path-POS-DEP-t']>
# <Feat rank=10 n=2 ig=0.3879 ['CfgFeat-CommonParent-Category', 'head1RootPathNgram-LEMMA-DEP-len4']>
# <Feat rank=30 n=3 ig=0.3754 ['head1RootPathNgram-POS-DEP-len2', 'head1head2Path-POS-DEP-t', 'head1head2Path-POS-DIRECTION-t']>
# <Feat rank=50 n=2 ig=0.3610 ['head1RootPathNgram-POS-DIRECTION-len4', 'head1head2Path-LEMMA-DEP-t']>
# <Feat rank=51 n=2 ig=0.3602 ['head1RootPathNgram-POS-DEP-len3', 'head1head2Path-NONE-DEP-t']>
# <Feat rank=60 n=2 ig=0.3567 ['head1RootPathNgram-NONE-DEP-len3', 'head1head2Path-POS-DIRECTION-t']>
# <Feat rank=87 n=2 ig=0.3428 ['head1RootPathNgram-NONE-DEP-len4', 'span1PosPat-FULL_POS-1-1']>
# <Feat rank=95 n=3 ig=0.3404 ['head1RootPathNgram-POS-DEP-len4', 'head1head2Path-POS-DIRECTION-t', 'span1PosPat-FULL_POS-1-1']>
# <Feat rank=129 n=2 ig=0.3346 ['head1RootPathNgram-POS-DEP-len4', 'span1PosPat-COARSE_POS-1-2']>
# <Feat rank=139 n=3 ig=0.3335 ['CfgFeat-DirectChildren-Rule', 'head1RootPathNgram-POS-DEP-len4', 'head1head2Path-NONE-DEP-t']>
# <Feat rank=151 n=2 ig=0.3327 ['CfgFeat-DirectChildren-Rule', 'head1RootPathNgram-POS-DIRECTION-len4']>
# <Feat rank=161 n=3 ig=0.3319 ['head1RootPathNgram-LEMMA-DEP-len4', 'span1PosPat-COARSE_POS-2-1', 'span1PosPat-FULL_POS-1-2']>
# <Feat rank=202 n=3 ig=0.3283 ['head1RootPath-POS-DIRECTION-t', 'head1RootPathNgram-POS-DEP-len4', 'span1PosPat-COARSE_POS-2-1']>
# <Feat rank=205 n=3 ig=0.3281 ['head1RootPathNgram-NONE-DEP-len4', 'span1PosPat-FULL_POS-0-2', 'span1PosPat-FULL_POS-2-1']>
# <Feat rank=244 n=2 ig=0.3246 ['Dist(SemaforPathLengths,Head2,Span1.First)', 'head1Bc1000/99']>
# <Feat rank=266 n=2 ig=0.3233 ['head1RootPathNgram-LEMMA-DEP-len3', 'span1PosPat-FULL_POS-1-2']>
# <Feat rank=269 n=2 ig=0.3232 ['head1head2Path-POS-DEP-t', 'sentenceBc1000/3']>
# <Feat rank=303 n=2 ig=0.3216 ['head1RootPathNgram-LEMMA-DIRECTION-len4', 'span1PosPat-FULL_POS-2-1']>
CfgFeat-CommonParent-Category*head1RootPathNgram-LEMMA-DEP-len4*head1head2Path-POS-DEP-t + head1RootPathNgram-LEMMA-DEP-len4*head1head2Path-POS-DEP-t + head1RootPathNgram-NONE-DEP-len4*head1head2Path-NONE-DEP-t*head1head2Path-POS-DEP-t + CfgFeat-CommonParent-Category*head1RootPathNgram-LEMMA-DEP-len4 + head1RootPathNgram-POS-DEP-len2*head1head2Path-POS-DEP-t*head1head2Path-POS-DIRECTION-t + head1RootPathNgram-POS-DIRECTION-len4*head1head2Path-LEMMA-DEP-t + head1RootPathNgram-POS-DEP-len3*head1head2Path-NONE-DEP-t + head1RootPathNgram-NONE-DEP-len3*head1head2Path-POS-DIRECTION-t + head1RootPathNgram-NONE-DEP-len4*span1PosPat-FULL_POS-1-1 + head1RootPathNgram-POS-DEP-len4*head1head2Path-POS-DIRECTION-t*span1PosPat-FULL_POS-1-1 + head1RootPathNgram-POS-DEP-len4*span1PosPat-COARSE_POS-1-2 + CfgFeat-DirectChildren-Rule*head1RootPathNgram-POS-DEP-len4*head1head2Path-NONE-DEP-t + CfgFeat-DirectChildren-Rule*head1RootPathNgram-POS-DIRECTION-len4 + head1RootPathNgram-LEMMA-DEP-len4*span1PosPat-COARSE_POS-2-1*span1PosPat-FULL_POS-1-2 + head1RootPath-POS-DIRECTION-t*head1RootPathNgram-POS-DEP-len4*span1PosPat-COARSE_POS-2-1 + head1RootPathNgram-NONE-DEP-len4*span1PosPat-FULL_POS-0-2*span1PosPat-FULL_POS-2-1 + Dist(SemaforPathLengths,Head2,Span1.First)*head1Bc1000/99 + head1RootPathNgram-LEMMA-DEP-len3*span1PosPat-FULL_POS-1-2 + head1head2Path-POS-DEP-t*sentenceBc1000/3 + head1RootPathNgram-LEMMA-DIRECTION-len4*span1PosPat-FULL_POS-2-1
