// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.api.data.pullrequest

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.intellij.collaboration.api.dto.GraphQLFragment
import com.intellij.collaboration.api.dto.GraphQLNodesDTO
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.plugins.github.api.data.*
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import java.util.*

@GraphQLFragment("/graphql/fragment/pullRequestInfoShort.graphql")
open class GHPullRequestShort(
  id: String,
  val url: String,
  val number: Long,
  @NlsSafe val title: String,
  val state: GHPullRequestState,
  val isDraft: Boolean,
  val author: GHActor?,
  val createdAt: Date,
  val updatedAt: Date,
  @JsonProperty("assignees") assignees: GraphQLNodesDTO<GHUser>,
  @JsonProperty("labels") labels: GraphQLNodesDTO<GHLabel>?,
  @JsonProperty("reviewRequests") reviewRequests: GraphQLNodesDTO<GHPullRequestReviewRequest>?,
  @JsonProperty("reviewThreads") reviewThreads: GraphQLNodesDTO<ReviewThreadDetails>,
  @JsonProperty("reviews") reviews: GraphQLNodesDTO<GHPullRequestReview>?,
  val mergeable: GHPullRequestMergeableState,
  val viewerCanUpdate: Boolean,
  val viewerCanReact: Boolean,
  val viewerDidAuthor: Boolean,
  override val reactions: GHReactable.ReactionConnection,
) : GHNode(id), GHReactable {
  @JsonIgnore
  val prId = GHPRIdentifier(id, number)

  @JsonIgnore
  val assignees = assignees.nodes

  @JsonIgnore
  val labels = labels?.nodes.orEmpty()

  @JsonIgnore
  val reviewRequests = reviewRequests?.nodes.orEmpty()

  @JsonIgnore
  val unresolvedReviewThreadsCount = reviewThreads.nodes.count { !it.isResolved && !it.isOutdated }

  @JsonIgnore
  val reviews: List<GHPullRequestReview> = reviews?.nodes.orEmpty()

  override fun toString(): String = "#$number $title"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    if (!super.equals(other)) return false

    other as GHPullRequestShort

    if (url != other.url) return false
    if (number != other.number) return false
    if (title != other.title) return false
    if (state != other.state) return false
    if (isDraft != other.isDraft) return false
    if (author != other.author) return false
    if (createdAt != other.createdAt) return false
    if (updatedAt != other.updatedAt) return false
    if (mergeable != other.mergeable) return false
    if (viewerCanUpdate != other.viewerCanUpdate) return false
    if (viewerDidAuthor != other.viewerDidAuthor) return false
    if (prId != other.prId) return false
    if (assignees != other.assignees) return false
    if (labels != other.labels) return false
    if (reviewRequests != other.reviewRequests) return false
    if (unresolvedReviewThreadsCount != other.unresolvedReviewThreadsCount) return false
    if (reviews != other.reviews) return false

    return true
  }

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + url.hashCode()
    result = 31 * result + number.hashCode()
    result = 31 * result + title.hashCode()
    result = 31 * result + state.hashCode()
    result = 31 * result + isDraft.hashCode()
    result = 31 * result + (author?.hashCode() ?: 0)
    result = 31 * result + createdAt.hashCode()
    result = 31 * result + updatedAt.hashCode()
    result = 31 * result + mergeable.hashCode()
    result = 31 * result + viewerCanUpdate.hashCode()
    result = 31 * result + viewerDidAuthor.hashCode()
    result = 31 * result + prId.hashCode()
    result = 31 * result + assignees.hashCode()
    result = 31 * result + labels.hashCode()
    result = 31 * result + reviewRequests.hashCode()
    result = 31 * result + unresolvedReviewThreadsCount
    result = 31 * result + reviews.hashCode()
    return result
  }

  class ReviewThreadDetails(val isResolved: Boolean, val isOutdated: Boolean)
}