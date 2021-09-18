package io.libp2p.core.crypto

import org.bouncycastle.jcajce.provider.digest.SHA1
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.bouncycastle.jcajce.provider.digest.SHA512

fun sha1(data: ByteArray): ByteArray = SHA1.Digest().digest(data)
fun sha256(data: ByteArray): ByteArray = SHA256.Digest().digest(data)
fun sha512(data: ByteArray): ByteArray = SHA512.Digest().digest(data)
