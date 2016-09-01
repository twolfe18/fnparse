namespace java edu.jhu.hlt.ikbp.data

// TODO Consider switching this to just a typedef to Id
// This will allow things like {type=FeatureType.MENTION_ID, name="wsj0001/mentionUuidSuffix"}
// index currently must serve either as 1) a feature hash/index OR 2) a feature type. Can't really do both.
/*
struct Feature {
  1: string key,
  2: i32 index,   // may not be set, may be hash, may be dense index
}
*/

struct Id {
  1: i32 type,
  2: i32 id,
  3: string name,
}

typedef Id Feature

struct Node {
  1: Id id,
  2: list<Feature> features,

  // I think mentions are better encoded as either features
  // or as other edges. A Node can represent a mention, where the
  // id is something like {type=MENTION, name="ecb+/t3/d11/m5"}
  //3: list<Id> mentions,
}

/* If you want to refer to an Edge in another edge, then you should create an
 * argument which represents a Hobbs' style "the fact that this is true"-style
 * argument.
 */
struct Edge {
  1: Id relation,
  2: list<Id> arguments,
  3: double score,
}

/* A pocket KB */
struct PKB {
  1: list<Node> nodes,  // Entities and Situations
  2: list<Edge> edges,  // coreference, srl, etc.

  /* Some Nodes' features will be mention ids which appear in one of these two
   * documents. I want these to be ids rather than the communications themselves
   * firstly so there is no concrete dependency and secondly so that a PKB can
   * be fairly efficiently sent over the wire.
   */
  3: list<string> documentIds,
}

struct Query {
  1: Id id,
  2: Node subject,
  3: PKB context,
}

struct Response {
  1: Id id,
  2: double score,
  3: PKB delta,   // Contains matching between one extra document and the Query PKB.
  // Edges with type="matching", args = [query enttity, other entities in in this document matching]
  // Every Edge must refer only to Nodes already in the Query's PKB or the delta
  4: Id center,   // Node id of the most relevant node (corresponding to the query's subject) in the delta
}

