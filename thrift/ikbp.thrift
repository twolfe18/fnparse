namespace java edu.jhu.hlt.ikbp.data

struct Id {
  1: i32 type,
  /* In general, either id or name should be used, or if both are used, there
   * should be a correspondence between them, e.g. an alphabet mapping them
   * together is maintained on the side, or id = hash(name). */
  2: i32 id,
  3: string name,
}

enum FeatureType {
  REGULAR,        // arbitrary string/int value for training models, e.g. "animate=true"
  MENTION_ID,     // string value like "ecb+/<topic>/<doc>/<mention>", though format is in general unrestricted
  CONCRETE_UUID,  // string value for pointing into a concrete Communication
  INTERCEPT,
  HEADWORD,
  ENTITY_TYPE,
  SITUATION_TYPE,
  // many more to come
}

/* A thing worth referring to, see NodeType */
struct Node {
  /* Contains the type and a name or id which jointly serve as a unique identifier */
  1: Id id,
  /* Edges can serve as features, but these are features which are probably not:
   * a) predicted by some system (with parameters) or
   * b) informative to Nodes other than this one
   *
   * These features are to be interpretted in the context of the node type,
   * e.g. "abstract=true" does not necessarily mean the same thing for ENTITY
   * nodes as it does for SITUATION nodes.
   */
  2: list<Id> features,
}

enum NodeType {
  ENTITY,
  SITUATION,
  // The last argument of FRAME, ROLE, and NER edges will
  // be replaced by node ids pointing to nodes of these types.
  // FRAME_TYPE,
  // ROLE_TYPE,
  // ENTITY_TYPE,
}

/* Something which can be true or false about a Node.
 * Equivalent to a hyperedge in a hypergraph representation
 * (graph edge would only have source and sink, less expressive).
 */
struct Edge {
  /* Contains the type and a name or id which jointly serve as a unique identifier */
  1: Id id,
  /* See EdgeType for what these values mean */
  2: list<Id> arguments,
  /* A confidence/veracity score, application specific interpretation */
  3: double score,
}

enum EdgeType {
  // Edges' argument patterns are listed to the right
  COREFERENCE,  // (node id 1, node id 2)
  FRAME,        // (situation node id, frame node id or name)
  ROLE,         // (situation node id, argument/filler node id, role node id or name)
  NER,          // (entity node id, entity type node id or name)
}

/* A pocket KB (can be viewed as a weighted hypergraph). */
struct PKB {
  1: list<Node> nodes,  // Entities and Situations
  2: list<Edge> edges,  // facts about nodes
  /* Some Nodes will ground out in mentions appearing in these documents */
  3: list<Id> documents,
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
  // All edges will have type COREFERENCE.
  // Every Edge must refer only to Nodes in the Query's PKB or the delta
  4: Id anchor,   // Node id of the most relevant node (corresponding to the query's subject) in the delta
}

