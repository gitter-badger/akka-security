package com.helloscala.akka.security.oauth.server.authentication

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.util.Timeout
import com.helloscala.akka.oauth.constant.OAuth2ParameterNames
import com.helloscala.akka.oauth.jwt.JWT
import com.helloscala.akka.oauth.OAuth2AccessToken
import com.helloscala.akka.oauth.TokenType
import com.helloscala.akka.security.authentication.Authentication
import com.helloscala.akka.security.authentication.AuthenticationProvider
import com.helloscala.akka.security.exception.AkkaSecurityException
import com.helloscala.akka.security.oauth.jose.JoseHeader
import com.helloscala.akka.security.oauth.server.authentication.client.RegisteredClient
import com.helloscala.akka.security.oauth.server.authentication.client.RegisteredClientRepository
import com.helloscala.akka.security.oauth.server.jwt.JwtEncoder
import com.helloscala.akka.security.oauth.server.OAuth2AuthorizationService
import com.helloscala.akka.security.oauth.server.OAuth2Extension
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * @author Yang Jing <a href="mailto:yang.xunjing@qq.com">yangbajing</a>
 * @date 2020-09-19 17:25:39
 */
trait OAuth2ClientCredentialsAuthenticationProvider extends AuthenticationProvider

class OAuth2ClientCredentialsAuthenticationProviderImpl(system: ActorSystem[_])
    extends OAuth2ClientCredentialsAuthenticationProvider {
  implicit private val ts = system
  implicit private val ec = system.executionContext
  implicit private val timeout: Timeout = 5.seconds

  override def authenticate(authentication: Authentication): Future[OAuth2AccessTokenAuthenticationToken] = {
    val oauth2Extension = OAuth2Extension(system)
    val oauthAuthentication = authentication.asInstanceOf[OAuth2AccessTokenAuthentication]

    val registeredClientFuture: Future[RegisteredClient] = oauthAuthentication.credentials match {
      case BasicHttpCredentials(clientId, clientSecret) =>
        oauth2Extension.registeredClientRepository
          .ask[Option[RegisteredClient]](replyTo => RegisteredClientRepository.FindByClientId(clientId, replyTo))
          .flatMap {
            case Some(registeredClient) =>
              if (registeredClient.clientSecret != clientSecret) {
                throw new AkkaSecurityException("Client secret not match.")
              }
              val scopes =
                if (oauthAuthentication.scopes.isEmpty) registeredClient.scopes
                else registeredClient.scopes.intersect(oauthAuthentication.scopes)
              Future.successful(registeredClient.copy(scopes = scopes))
            case None =>
              Future.failed(new AkkaSecurityException("Client not found."))
          }
      case _ =>
        Future.failed(new AkkaSecurityException("Need basic http credentials."))
    }

    def generateJwt(registeredClient: RegisteredClient): Future[JWT] = {
      val message = OAuth2ClientCredentialsAuthentication(
        registeredClient,
        oauthAuthentication.grantType,
        registeredClient.scopes,
        true)

      val issuedAt = Instant.now()
      val expiresAt = issuedAt.plus(7, ChronoUnit.DAYS).plus(5, ChronoUnit.MINUTES)
      val jwtHeader = JoseHeader(new JWSHeader.Builder(JWSAlgorithm.RS256).build())
      val jwtClaim = new JWTClaimsSet.Builder()
        .issuer("https://akka-security.helloscala.com")
        .subject(registeredClient.clientId)
        .issueTime(java.util.Date.from(issuedAt))
        .expirationTime(java.util.Date.from(expiresAt))
        .claim(OAuth2ParameterNames.SCOPE, registeredClient.scopes.mkString(" "))
        .build()

      oauth2Extension.jwtEncoder.askWithStatus[JWT](replyTo => JwtEncoder.Encode(message, jwtHeader, jwtClaim, replyTo))
    }

    val accessTokenFuture = for {
      registeredClient <- registeredClientFuture
      jwt <- generateJwt(registeredClient)
    } yield {
      val accessToken: OAuth2AccessToken =
        OAuth2AccessToken(jwt.tokenValue, jwt.issuedAt, jwt.expiresAt, TokenType.BEARER, registeredClient.scopes)
      OAuth2AccessTokenAuthenticationToken(registeredClient, accessToken)
    }

    accessTokenFuture.foreach(token => oauth2Extension.authorizationService ! OAuth2AuthorizationService.Save(token))

    accessTokenFuture
  }

}
