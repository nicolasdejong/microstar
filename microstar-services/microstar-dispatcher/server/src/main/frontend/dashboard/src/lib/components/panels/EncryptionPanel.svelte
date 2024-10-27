<script lang="ts">

    import {fetchData, fetchText} from "../../utils/Network";
    import {debounce} from "../../utils/Utils.js";

    let hashInput: string = '';
let hashResult: string = '';
let hashSalt: string = '';
let hashSpecial: boolean = false;

let encryptInput: string = '';
let encryptResult: string = '';
let encPassword: string = '';
let encSalt: string = '';
let encSpecial: boolean = false;

let decryptInput: string = '';
let decryptResult: string = '';
let decPassword: string = '';
let decSalt: string = '';
let decSpecial: boolean = false;

let useCustomTokenSecret: boolean = false;
let customTokenSecret: string = '';
const initGeneratedUserToken = JSON.parse(localStorage.userTokenGenerator || 'null') || { tokenId: '0', tokenName: '', tokenEmail: '', tokenLifetime: '24h' };
let tokenId: string = initGeneratedUserToken.tokenId;
let tokenName: string = initGeneratedUserToken.tokenName;
let tokenEmail: string = initGeneratedUserToken.tokenEmail;
let tokenLifetime: string = initGeneratedUserToken.tokenLifetime;
let userTokenText: string = '';
let tokenResult: string = '';
let testToken: string = '';
let testTokenResult: any = {};

let uuid: string = '';

$: useCustomTokenSecret, customTokenSecret, tokenId, tokenName, tokenEmail, tokenLifetime, debounce(generateUserToken);
$: testToken, debounce(getTestTokenInfo);

function generateHash() {
    fetchText('/hash/' + encodeURIComponent(hashInput), 'get', null, { 'X-HASH-SALT': (hashSpecial && hashSalt) || '' })
        .then(text => hashResult = text)
        .catch(_ => hashResult = 'failed');
}
function encrypt() {
    fetchText('/encrypt/' + encodeURIComponent(encryptInput), 'get', null, {
        ...(encSpecial && !!encSalt     && { 'X-ENC-SALT':     encSalt }),
        ...(encSpecial && !!encPassword && { 'X-ENC-PASSWORD': encPassword })
    })
        .then(text => encryptResult = decodeURIComponent(text))
        .catch(_ => encryptResult = 'failed');
}
function decrypt() {
    fetchText('/decrypt/' + encodeURIComponent(decryptInput), 'get', null, {
        ...(decSpecial && !!decSalt     && { 'X-ENC-SALT':     decSalt }),
        ...(decSpecial && !!decPassword && { 'X-ENC-PASSWORD': decPassword })
    })
        .then(text => decryptResult = text)
        .catch(_ => decryptResult = 'failed');
}
function generateUUID() {
    fetchText('/random-uuid').then(randomUUID => uuid = randomUUID);
}
function generateUserToken() {
    localStorage.userTokenGenerator = JSON.stringify({tokenId,tokenName,tokenEmail,tokenLifetime});
    fetchText('/generate-user-token?'
        + 'id=' + (tokenId || '1')
        + '&name=' + (tokenName || 'user')
        + '&email=' + (tokenEmail || 'user@domain.net')
        + '&lifetime=' + (tokenLifetime || '24h')
        + (useCustomTokenSecret ? '&customSecret=' + customTokenSecret : '')
    ).then(token => {
        userTokenText = token;
        fetchText('/user-token/' + encodeURIComponent(token)).then(ut => tokenResult = ut);
    });
}
function getTestTokenInfo() {
    fetchData('/whoami/' + encodeURIComponent(testToken)).then(result => testTokenResult = result);
}
</script>

<div style="padding: 1em 1em 0 1em;">
    Passwords are hashed and stored in <b>authentication.users</b> (as a map from username to password-hash) in the authorization.yml file.<br>
    Roles are store in <b>authentication.userRoles</b> (as a map from username to a list of roles) in the services.yml file (because all services need it).<br>
    Secret values can be encrypted and added as values with the {'{cipher}'} (or !cipher!) prefix.<br>
</div>

<div class="hashPanel">
    <table>
        <tr class="title">
            <td colspan="3">Password hashing function</td>
        </tr>
        <tr class="subtitle">
            <td colspan="3">Used for settings in authentication.userPasswords</td>
        </tr>
        <tr class="subtitle last">
            <td colspan="3">Set at least one password or all users will be allowed</td>
        </tr>
        <tr class="smaller">
            {#if hashSpecial}
                <td>hashSalt:</td>
                <td colspan="2">
                    <input type="text" bind:value={hashSalt} placeholder="leave empty for default">
                    <input type="checkbox" bind:checked={hashSpecial}>Different salt
                </td>
            {:else}
                <td colspan="3">
                    <input type="checkbox" bind:checked={hashSpecial}>Use different salt than configured
                </td>
            {/if}
        </tr>
        <tr class="spacer"></tr>
        <tr>
            <td>Input:</td>
            <td><input type="text" bind:value={hashInput} on:keydown={evt => { if(evt.key === 'Enter') generateHash(); }}></td>
            <td><button on:click={() => generateHash()}>Hash</button></td>
        </tr>
        <tr>
            <td>Result:</td>
            <td>{hashResult}</td>
        </tr>
    </table>
</div> <!-- hash panel -->

<div style="display:flex;flex-direction: row;">

<div class="encryptPanel">
    <table>
        <tr class="title">
            <td colspan="3">Encryption of strings</td>
        </tr>
        <tr class="subtitle">
            <td colspan="3">Can be used in settings values, prefixed with '{'{cipher}'}'</td>
        </tr>
        <tr class="subtitle last">
            <td colspan="3">Encryption will change with encryption.encPassword setting changes</td>
        </tr>
        <tr class="smaller">
            {#if encSpecial}
                <td>encPassword:</td>
                <td colspan="2">
                            <input type="text" bind:value={encPassword} placeholder="leave empty for default">
                    encSalt <input type="text" bind:value={encSalt}     placeholder="leave empty for default">
                    <input type="checkbox" bind:checked={encSpecial}>Different password/salt
                </td>
            {:else}
                <td colspan="3">
                    <input type="checkbox" bind:checked={encSpecial}>Use different password/salt than configured
                </td>
            {/if}
        </tr>
        <tr class="spacer"></tr>
        <tr>
            <td>Input:</td>
            <td><input type="text" bind:value={encryptInput} on:keydown={evt => { if(evt.key === 'Enter') encrypt(); }}></td>
            <td><button on:click={() => encrypt()}>Encrypt</button></td>
        </tr>
        <tr>
            <td>Result:</td>
            <td>{encryptResult}</td>
        </tr>
    </table>
</div> <!-- encrypt panel -->

<div class="decryptPanel">
    <table>
        <tr class="title">
            <td colspan="3">Decryption of strings</td>
        </tr>
        <tr class="subtitle last">
            <td colspan="3">Decryption will change with encryption.encPassword setting changes</td>
        </tr>
        <tr class="smaller">
            {#if decSpecial}
                <td>encPassword</td>
                <td colspan="2">
                                 <input type="text" bind:value={decPassword} placeholder="leave empty for default">
                    encSalt:     <input type="text" bind:value={decSalt}     placeholder="leave empty for default">
                    <input type="checkbox" bind:checked={decSpecial}>Different password/salt
                </td>
            {:else}
                <td colspan="3">
                    <input type="checkbox" bind:checked={decSpecial}>Use different password/salt than configured
                </td>
            {/if}
        </tr>
        <tr class="spacer"></tr>
        <tr>
            <td>Input:</td>
            <td><input type="text" bind:value={decryptInput} on:keydown={evt => { if(evt.key === 'Enter') decrypt(); }}></td>
            <td><button on:click={() => decrypt()}>Decrypt</button></td>
        </tr>
        <tr>
            <td>Result:</td>
            <td>{decryptResult}</td>
        </tr>
    </table>
</div> <!-- decrypt panel -->

</div> <!-- encrypt/decrypt panels -->

<div class="tokenPanel">
    <table>
        <tr class="title">
            <td colspan="3">User token generation</td>
        </tr>
        <tr class="subtitle last">
            <td colspan="3">Used when testing for specific user (roles)</td>
        </tr>
        <tr class="smaller">
            <td colspan="3">
                <input type="checkbox" bind:checked={useCustomTokenSecret}>Use different secret than configured
            {#if useCustomTokenSecret}
                <br>
                Secret: <input type="text" bind:value={customTokenSecret} placeholder="secret">
            {/if}
            </td>
        </tr>
        <tr class="spacer"></tr>
        <tr>
            <td>Id:</td>
            <td><input type="text" bind:value={tokenId}></td>
        </tr>
        <tr>
            <td>Name:</td>
            <td><input type="text" bind:value={tokenName}></td>
        </tr>
        <tr>
            <td>Email:</td>
            <td><input type="text" bind:value={tokenEmail}></td>
        </tr>
        <tr>
            <td>Lifetime:</td>
            <td><input type="text" bind:value={tokenLifetime}> (unit suffix: s, m, h, d, w))</td>
        </tr>
        <tr>
            <td>Token:</td>
            <td>{userTokenText}</td>
        </tr>
        <tr>
            <td>Result:</td>
            <td>{tokenResult}</td>
        </tr>
    </table>
</div> <!-- token panel -->

<div class="tokenInfoPanel" style="margin-top:2em;">
    <table>
        <tr class="title">
            <td colspan="3">User token content</td>
        </tr>
        <tr class="subtitle last">
            <td colspan="3">Get data from existing token</td>
        </tr>
        <tr class="smaller">
            <td colspan="3">
                <input type="checkbox" bind:checked={useCustomTokenSecret}>Use different secret than configured
                {#if useCustomTokenSecret}
                    <br>
                    Secret: <input type="text" bind:value={customTokenSecret} placeholder="secret">
                {/if}
            </td>
        </tr>
        <tr class="spacer"></tr>
        <tr>
            <td>Token:</td>
            <td><input type="text" size="120" bind:value={testToken}></td>
        </tr>
        <tr> <td>Id:</td>     <td>{testTokenResult.id}</td> </tr>
        <tr> <td>Name:</td>   <td>{testTokenResult.name}</td> </tr>
        <tr> <td>Email:</td>  <td>{testTokenResult.email}</td> </tr>
        <tr> <td>Expire:</td> <td>{testTokenResult.tokenExpire}</td> </tr>
    </table>
</div> <!-- token panel -->

<div class="uuidPanel" style="margin-top:2em;">
    <table>
        <tr class="title">
            <td colspan="3">UUID generator</td>
        </tr>
        <tr class="subtitle last">
            <td colspan="3">Asks a random UUID from the server</td>
        </tr>
        <tr>
            <td><input type="text" bind:value={uuid} style:width="22em"></td>
            <td><button on:click={() => generateUUID()}>Generate</button></td>
        </tr>
    </table>
</div> <!-- uuid panel -->



<style lang="scss">
    .hashPanel, .encryptPanel, .decryptPanel, .tokenPanel, .tokenInfoPanel, .uuidPanel {
      display: block;
      width: fit-content;
      border: 2px solid var(--border-color);
      margin: 1em;

      table {
        border-collapse: collapse;

        td:first-child { padding-left: 0.5em; }
        td {
          padding: 0.2em;
        }

        tr.title td {
          background-color: var(--background-color-faded);
          padding: 0 0 0 0.2em;
        }
        tr.subtitle td {
          background-color: var(--background-color-faded);
          font-size: 75%;
          line-height: 0.8em;
          padding: 0 0 0.4em 0.2em;
        }
        tr.subtitle.last td {
          border-bottom: 1px solid var(--border-color);
        }
        tr.spacer {
          height: 0.5em;
        }
        tr.smaller {
          text-align: left;
          opacity: 0.7;
          td {
            border-bottom: 1px solid var(--border-color);
          }
        }
      }
    }
</style>