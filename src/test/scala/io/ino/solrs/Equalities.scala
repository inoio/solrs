package io.ino.solrs

import org.scalactic
import org.scalactic.Equality
import org.scalactic.Uniformity

object Equalities {

  /**
    * Reduces a [[ShardReplica]] to [[SolrServer]], so that only url and status are compared
    * (ignoring [[ShardReplica]]'s `isLeader` and `replicaType`).
    */
  implicit val solrServerStatusEquality: Equality[SolrServer] = scalactic.Equality(new Uniformity[SolrServer] {

    override def normalizedOrSame(b: Any): Any = b match {
      case s: ShardReplica => SolrServer(s.baseUrl, s.status)
      case s: SolrServer => s
    }

    override def normalizedCanHandle(b: Any): Boolean = b.isInstanceOf[SolrServer]

    override def normalized(a: SolrServer): SolrServer = normalizedOrSame(a).asInstanceOf[SolrServer]

  })

}
