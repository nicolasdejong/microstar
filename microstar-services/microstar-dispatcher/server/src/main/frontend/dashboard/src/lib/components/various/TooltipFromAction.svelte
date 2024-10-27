<script>
    // Based on REPL: https://svelte.dev/repl/dd6754a2ad0547c5b1c1ea37c0293fef?version=3.49.0
    export let title;
    export let x;
    export let y;
</script>

<div style="
		top: {y + 20}px;
		left: {x + 2}px;">{title}</div>

<style>
    div {
        border: 1px solid #ddd;
        box-shadow: 1px 1px 1px #ddd;
        background: var(--background-color);
        color: var(--text-color);
        border-radius: 4px;
        padding: 4px;
        position: absolute;
        z-index: 999;
    }
</style>
