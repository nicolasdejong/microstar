<script>
    import {createEventDispatcher} from 'svelte';
    import {userPasswordStore} from "../../stores/UserPasswordStore.ts";
    import {clearUserData, getUserInfo} from "../../utils/Network.ts";

    const dispatch = createEventDispatcher();
    const ok = () => {
        userPasswordStore.set(username, password);
        getUserInfo().then(() => location.reload())
    }
    const close = () => { isVisible = false; }

    let modal;
    let username = userPasswordStore.get()?.username;
    let password = '';
    let isVisible = false;
    let nameNode;
    let pwNode;

    userPasswordStore.subscribe(up => {
        getUserInfo().catch(_ => null).then(userInfo => {
            if(userInfo?.name) return;

            // Only show login dialog if no SSO
            username = up?.username;
            password = up?.password;
            if (!username) clearUserData();
            isVisible = !password;
            if(!password) (username ? pwNode : nameNode).focus();
        });
    })

    const handle_keydown = e => {
        if(!isVisible) return;
        if (e.key === 'Enter') { ok(); return; }
        if (e.key === 'Tab') {
            const nodes = modal.querySelectorAll('*');
            const tabbable = Array.from(nodes).filter(n => n.tabIndex >= 0);

            let index = tabbable.indexOf(document.activeElement);
            if (index === -1 && e.shiftKey) index = 0;

            index += tabbable.length + (e.shiftKey ? -1 : 1);
            index %= tabbable.length;

            tabbable[index].focus();
            e.preventDefault();
        }
    };
</script>

<svelte:window on:keydown={handle_keydown}/>

<div class="modal-background" on:click={close} class:visible={isVisible}></div>

<div class="modal" role="dialog" aria-modal="true" bind:this={modal} class:visible={isVisible}>
    <div class="content header">
        Login is required
    </div>
    <div class="content body">
        <table>
            <tr> <td>User name:</td> <td> <input bind:value={username}                 tabindex="1" bind:this={nameNode}> </td> </tr>
            <tr> <td>Password:</td>  <td> <input bind:value={password} type="password" tabindex="2" bind:this={pwNode}> </td> </tr>
        </table>
    </div>

    <button on:click={ok}>Login</button>
</div>

<style lang="scss">
    .modal-background {
      &:not(.visible) { display: none; }
      position: fixed;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      background: rgba(0, 0, 0, 0.5);
      z-index: 99;
    }

    .modal {
      &:not(.visible) { top: -100%; }
      position: absolute;
      display: flex;
      flex-direction: column;
      transition: all;
      transition-duration: 0.3s;
      left: 50%;
      top: 50%;
      bottom: auto;
      max-width: calc(100vw - 4em);
      max-height: calc(100vh - 4em);
      overflow: hidden;
      transform: translate(-50%, -50%);
      padding: 0 0 0.5em 0;
      border-radius: 0.2em;
      border: 1px solid var(--border-color-hover);
      z-index: 101;

      .content {
        padding: 1em;
        border-color: var(--border-color-hover);
        border-style: solid;
        border-width: 1px 0 1px 0;
      }

      .content.header {
        font-size: 150%;
        font-weight: bold;
      }

      .content.body {
        overflow: auto;

        table {
          td:first-child { font-weight: bold; padding-right: 0.5em; vertical-align: top; }
        }
      }
    }
    button {
        display: block;
        border: 1px solid var(--border-color-hover);
        margin: 1em auto 0 auto;
        padding: 0.2em 0.5em;
    }
    @media (prefers-color-scheme: dark) {
      .modal {
        background-color: #222;
        color: white;
        .content.body {
          background-color: #555;
        }
      }
    }
    @media (prefers-color-scheme: light) {
      .modal {
        background-color: #aaa;
        color: black;
        .content.body {
          background-color: #999;
        }
      }
    }
</style>