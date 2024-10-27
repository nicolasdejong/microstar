<script>
    import {createEventDispatcher, onDestroy} from 'svelte';
    import {fade, scale} from 'svelte/transition';

    const dispatch = createEventDispatcher();
    const close = () => dispatch('close');

    let modal;

    const handle_keydown = e => {
        if (e.key === 'Escape') {
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

    const previously_focused = typeof document !== 'undefined' && document.activeElement;

    if (previously_focused) {
        onDestroy(() => {
            previously_focused.focus();
        });
    }
</script>

<svelte:window on:keydown={handle_keydown}/>

<div class="modal-background" on:click={close} transition:fade={{duration:100}}></div>

<div class="modal" role="dialog" aria-modal="true" bind:this={modal} transition:scale={{duration:150}}>
    <div class="content header">
        <slot name="header"></slot>
        <slot name="subHeader"></slot>
    </div>
    <div class="content body">
        <slot></slot>
    </div>

    <div class="content footer">
        <slot name="footer"></slot>

        <!-- svelte-ignore a11y-autofocus -->
        <button on:click={close}>close</button>
    </div>

</div>

<style>
    .modal-background {
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background: rgba(0,0,0,0.3);
        z-index: 99;
    }

    .modal {
        position: absolute;
        display: flex;
        flex-direction: column;
        left: 50%;
        top: 50%;
        max-width: calc(100vw - 6em);
        max-height: calc(100vh - 6em);
        overflow: hidden;
        transform: translate(-50%,-50%);
        padding: 0;
        border-radius: 0.2em;
        border: 2px solid var(--border-color-hover);
        background: var(--background-color);
        color: var(--text-color);
        z-index: 100;
    }
    .modal .content {
        padding: 0.5em;
        border-color: var(--border-color-hover);
        border-style: solid;
        border-width: 1px 0 1px 0;
    }
    .modal .content.header {
        font-size: 150%;
        font-weight: bold;
        text-align: center;
    }
    .modal .content.body {
        padding: 0.75em;
        overflow: auto;
    }
    .modal:global .content.header > div:nth-child(2) {
        font-size: 60%;
        text-align: center;
    }

    button {
        display: block;
        border: 1px solid var(--border-color-hover);
        margin: 0 auto 0 auto;
        padding: 0.2em 0.5em;
    }
</style>