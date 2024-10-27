<script>
    import {createEventDispatcher} from 'svelte';
    import {get} from 'svelte/store';
    import {errorStore} from "../../stores/ErrorStore";

    const dispatch = createEventDispatcher();
    const close = () => { errorStore.reset(); dispatch('close'); }

    let modal;

    const handle_keydown = e => {
        if(!get(errorStore)) return;
        if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') {
            close();
            return;
        }

        if (e.key === 'Tab') {
            // trap focus
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

<div class="modal-background" on:click={close} class:visible={$errorStore}></div>

<div class="modal" role="dialog" aria-modal="true" bind:this={modal} class:visible={$errorStore}>
    <div class="content header">
        Server error
    </div>
    <div class="content body">
        {#if $errorStore}
            {#if $errorStore.name}
                <table>
                    <tr> <td>Service:</td>      <td>{$errorStore.name || '?'}</td> </tr>
                    <tr> <td>ServiceGroup:</td> <td>{$errorStore.serviceGroup || ''}</td> </tr>
                    <tr> <td>Version:</td>      <td>{$errorStore.version || ''}</td> </tr>
                    <tr> <td>Type:</td>         <td>{$errorStore.type || ''}</td> </tr>
                    <tr> <td>Error:</td>        <td>{$errorStore.error || ''}</td> </tr>
                    {#if $errorStore.stacktrace}
                    <tr> <td>Stack trace:</td>  <td style="white-space:pre;">{$errorStore.stacktrace || ''}</td> </tr>
                    {/if}
                </table>
            {:else}
              {#if typeof $errorStore === 'object'}
                <table>
                    {#each [...new Map(Object.entries($errorStore))] as [key, value]}
                        {#if value !== undefined}
                            <tr> <td>{key}</td> <td>{value} </td></tr>
                        {/if}
                    {/each}
                </table>
              {:else}
                {$errorStore}
              {/if}
            {/if}
        {/if}
    </div>

    <button on:click={close}>close</button>
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
      z-index: 100;

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
        background-color: #800;
        color: white;
        .content.body {
          background-color: #500;
        }
      }
    }
    @media (prefers-color-scheme: light) {
      .modal {
        background-color: #f44;
        color: black;
        .content.body {
          background-color: #f99;
        }
      }
    }
</style>