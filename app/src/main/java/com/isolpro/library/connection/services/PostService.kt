package com.isolpro.library.connection.services

import android.content.Context
import com.isolpro.library.connection.Connection
import com.isolpro.library.connection.helpers.ConnectionHelper
import com.isolpro.library.connection.models.Post

object PostService {

  fun getPosts(ctx: Context): Connection<Post> {
    return ConnectionHelper(ctx, Post::class.java)
      .endpoint("/posts")
      .loader(false)
  }

  fun createPost(ctx: Context, post: Post): Connection<Post> {
    return ConnectionHelper(ctx, Post::class.java)
      .payload(post)
      .endpoint("/posts")
      .loader(false)
  }

}