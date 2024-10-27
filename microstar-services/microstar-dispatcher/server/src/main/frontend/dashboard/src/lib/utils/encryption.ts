// derive string key
async function deriveKey(password) {
    const algo = {
        name: 'PBKDF2',
        hash: 'SHA-256',
        salt: new TextEncoder().encode('a-unique-salt'),
        iterations: 1000
    }
    return crypto.subtle.deriveKey(
        algo,
        await crypto.subtle.importKey(
            'raw',
            new TextEncoder().encode(password),
            {
                name: algo.name
            },
            false,
            ['deriveKey']
        ),
        {
            name: 'AES-GCM',
            length: 256
        },
        false,
        ['encrypt', 'decrypt']
    )
}

// Encrypt function
export async function encrypt(text, password) {
    const algo = {
        name: 'AES-GCM',
        length: 256,
        iv: crypto.getRandomValues(new Uint8Array(12))
    }
    return {
        cipherText: await crypto.subtle.encrypt(
            algo,
            await deriveKey(password),
            new TextEncoder().encode(text)
        ),
        iv: algo.iv
    }
}

// Decrypt function
export async function decrypt(encrypted, password) {
    const algo = {
        name: 'AES-GCM',
        length: 256,
        iv: encrypted.iv
    }
    return new TextDecoder().decode(
        await crypto.subtle.decrypt(
            algo,
            await deriveKey(password),
            encrypted.cipherText
        )
    )
}

// example
;(async () => {
    // encrypt
    const encrypted = await encrypt('Secret text', 'password')

    console.log('encrypted: ', encrypted);

    // the cipher text
    console.log(
        String.fromCharCode.apply(null, new Uint8Array(encrypted.cipherText))
    )

    // decrypt it
    const decrypted = await decrypt(encrypted, 'password')
    console.log(decrypted) // Secret text
})()