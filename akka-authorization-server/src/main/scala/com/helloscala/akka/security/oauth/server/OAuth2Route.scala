package com.helloscala.akka.security.oauth.server

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.helloscala.akka.security.oauth.server.authentication.OAuth2AccessTokenAuthenticationToken
import com.helloscala.akka.security.oauth.server.crypto.keys.KeyManager
import com.helloscala.akka.security.oauth.server.crypto.keys.KeyUtils
import com.helloscala.akka.security.oauth.server.crypto.keys.ManagedKey
import com.helloscala.akka.security.oauth.server.directives.OAuth2Directive
import com.nimbusds.jose.jwk.JWKSet

import scala.concurrent.duration._

/**
 * @author Yang Jing <a href="mailto:yang.xunjing@qq.com">yangbajing</a>
 * @date 2020-09-19 16:31:19
 */
class OAuth2Route(system: ActorSystem[_]) extends OAuth2Directive {
  private val oauth2Extension = OAuth2Extension(system)
  def route: Route =
    pathPrefix("oauth2") {
      (path("token") & post) {
        oauth2TokenAuthentication
      } ~
      (path("jwks") & get) {
        jwks
      }
    }

  def oauth2TokenAuthentication: Route =
    extractOAuth2TokenAuthentication { authentication =>
      val authenticationTokenFuture = oauth2Extension.clientCredentialsAuthenticationProvider
        .authenticate(authentication)
        .mapTo[OAuth2AccessTokenAuthenticationToken]
      onSuccess(authenticationTokenFuture) { authenticationToken =>
        complete(authenticationToken.token.toHttpEntity)
      }
    }

  def jwks: Route = {
    import scala.jdk.CollectionConverters._
    implicit val scheduler = system.scheduler
    implicit val timeout: Timeout = 5.seconds
    val keysFuture = oauth2Extension.keyManager.ask[Set[ManagedKey]](replyTo => KeyManager.FindAll(replyTo))
    onSuccess(keysFuture) { keys =>
      val jwks =
        keys.view.filter(managedKey => managedKey.isActive && managedKey.isAsymmetric).flatMap(KeyUtils.convert).toList
      val jwkSet = new JWKSet(jwks.asJava)
      complete(HttpEntity(ContentTypes.`application/json`, jwkSet.toString))
    }
  }
}
