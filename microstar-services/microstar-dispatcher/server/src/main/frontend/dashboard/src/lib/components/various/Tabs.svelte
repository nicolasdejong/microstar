<script>
    export let items = [];
    export let activeTabValue = 1;

    const handleClick = tabValue => () => (activeTabValue = tabValue);
</script>

<div class="tabs">
    <ul>
        {#each items as item}
            {#if !item.disabled}
                <li class={activeTabValue === item.value ? 'active' : ''}>
                    <span on:click={handleClick(item.value)}>{item.label}</span>
                </li>
            {/if}
        {/each}
    </ul>
    {#each items as item}
        {#if !item.disabled}
            <div class="content-box" class:active={activeTabValue === item.value}>
                <svelte:component this={item.component}/>
            </div>
        {/if}
    {/each}
</div>

<style>
    .tabs {
        flex: 1;
        display: flex;
        flex-flow: column;
        height: 100%;
        padding: 0 0.5rem;
        overflow: auto;
    }
    ul {
        display: flex;
        flex-wrap: wrap;
        padding-left: 0;
        margin: 0.5rem 0 0 0;
        list-style: none;
        border-bottom: 1px solid var(--border-color);
        user-select: none;
    }
    li {
        margin-bottom: -1px;
    }
    .content-box {
        display: none;
        flex: 1;
        margin-bottom: 10px;
        border: 1px solid var(--border-color);
        border-radius: 0 0 .5rem .5rem;
        border-top: 0;
        overflow: auto;
    }
    .content-box.active {
        display: initial;
    }

    span {
        border: 1px solid transparent;
        border-top-left-radius: 0.25rem;
        border-top-right-radius: 0.25rem;
        display: block;
        padding: 0.5rem 1rem;
        cursor: pointer;
        color: var(--text-color-unselected);
    }

    span:hover {
        border-color: var(--border-color-hover) var(--border-color-hover) var(--border-color);
        color: var(--text-color-hover);
    }

    li.active > span {
        border-color: var(--border-color) var(--border-color) var(--background-color);
        color: var(--text-color);
    }
</style>