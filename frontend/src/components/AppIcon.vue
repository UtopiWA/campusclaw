<script setup>
// Centralize the small inline SVG set so navigation and actions need no additional icon dependency.
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 20 },
})
</script>

<template>
  <svg
    class="app-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'home'">
      <path d="m3 11 9-8 9 8" /><path d="M5 10v10h14V10" /><path d="M9 20v-6h6v6" />
    </template>
    <template v-else-if="name === 'book'">
      <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 0 4 22z" />
      <path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5A2.5 2.5 0 0 1 20 22z" />
    </template>
    <template v-else-if="name === 'bot'">
      <rect x="4" y="7" width="16" height="12" rx="3" /><path d="M12 3v4" /><path d="M9 12h.01M15 12h.01" /><path d="M9 16h6" />
    </template>
    <template v-else-if="name === 'homework'">
      <path d="M8 4h8" /><path d="M9 2h6v4H9z" /><rect x="5" y="4" width="14" height="18" rx="2" /><path d="m9 14 2 2 4-5" />
    </template>
    <template v-else-if="name === 'audit'">
      <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
    </template>
    <template v-else-if="name === 'logout'">
      <path d="M10 17l5-5-5-5M15 12H3" /><path d="M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5" />
    </template>
    <template v-else-if="name === 'upload'">
      <path d="M12 16V3M7 8l5-5 5 5" /><path d="M5 14v6h14v-6" />
    </template>
    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" />
    </template>
    <template v-else-if="name === 'file'">
      <path d="M6 2h8l4 4v16H6z" /><path d="M14 2v5h5M9 13h6M9 17h4" />
    </template>
    <template v-else-if="name === 'eye'">
      <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12" /><circle cx="12" cy="12" r="2.5" />
    </template>
    <template v-else-if="name === 'download'">
      <path d="M12 3v13M7 11l5 5 5-5" /><path d="M5 21h14" />
    </template>
    <template v-else-if="name === 'edit'">
      <path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z" /><path d="m13.5 6.5 4 4" />
    </template>
    <template v-else-if="name === 'trash'">
      <path d="M4 7h16M9 3h6l1 4H8zM7 7l1 14h8l1-14M10 11v6M14 11v6" />
    </template>
    <template v-else-if="name === 'close'">
      <path d="m6 6 12 12M18 6 6 18" />
    </template>
    <template v-else-if="name === 'layers'">
      <path d="m12 2 9 5-9 5-9-5z" /><path d="m3 12 9 5 9-5M3 17l9 5 9-5" />
    </template>
    <template v-else-if="name === 'refresh'">
      <path d="M20 7v5h-5" /><path d="M4 17v-5h5" /><path d="M6.1 8a7 7 0 0 1 11.6-2.6L20 8M4 16l2.3 2.6A7 7 0 0 0 17.9 16" />
    </template>
    <template v-else-if="name === 'shield'">
      <path d="M12 22s8-3.5 8-10V5l-8-3-8 3v7c0 6.5 8 10 8 10" /><path d="m9 12 2 2 4-5" />
    </template>
    <template v-else-if="name === 'lock'">
      <rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </template>
    <template v-else-if="name === 'user'">
      <circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" />
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="9" />
    </template>
  </svg>
</template>
