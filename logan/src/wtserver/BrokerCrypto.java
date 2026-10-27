package wtserver;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/*
 * WolfTeam broker crypto — REVERSED & VALIDATED against the live client.
 *
 *  HEADER (first 8 bytes of every frame):
 *     plaintext layout: [0]=rand [1:2]=msgId(LE) [3:4]=seq(LE) [5]=rand
 *                       [6]=payloadBlockCount [7]=checksum(sum[0..6] & 0xff)
 *     cipher: whiten(header) then *modified* Blowfish-ECB (key "WolfTeam").
 *        - F(x) = (S0[b3]+S1[b2]) ^ (S2[b1]+S3[b0])      <-- NON-standard grouping
 *        - little-endian 32-bit words
 *        - tables below were dumped live AND reproduced exactly by key-scheduling
 *          the ASCII string "WolfTeam" with this modified F (constant every session).
 *     decode = BlowfishDec -> unwhiten ; encode = whiten -> BlowfishEnc
 *     VALIDATED: decode(c9729f69ced9bb70)=47021202000a0067 (msgId 0x1202, cks ok).
 *
 *  PAYLOAD (the bytes after the 8-byte header, [6]*16 of them):
 *     AES-128-ECB, Key B = e1397ef5372c0e4e4ee2b370562668a2  (from 0x4E28E0).
 *     *** PROVISIONAL — not yet round-trip-validated on a real multi-block frame. ***
 */
public final class BrokerCrypto {
    private static final int[] P = {
        0x0bda4b45, 0x793bb343, 0x0be8593b, 0x815cd250, 0x248bf109, 0xd615fbf1, 0x75bd27a8, 0x95a26650,
        0x103bd1e8, 0xc616a0e9, 0x0d5cb4fd, 0x0465c1e0, 0xcc0dbfb5, 0x77ea7d37, 0x7d5a65df, 0x869ea42a,
        0xe6d23cec, 0x4db1d60f,
    };
    private static final int[] S0 = {
        0xece30729, 0xd141c6c8, 0xc7094a3e, 0x9abcbad6, 0x2959cc2f, 0x521ac585, 0x41509365, 0xf52f86ea,
        0xdfa1d4f5, 0x7a9ad966, 0x7b14550f, 0xff2dd6c6, 0x3b8a24cb, 0x3c1e19ff, 0xb18f09aa, 0xc1f873ea,
        0xfe85a454, 0xf5fffe34, 0xb55336dc, 0xb393cd4b, 0x20a7c944, 0xcf70f02c, 0xa8972275, 0x74847eae,
        0x8def804d, 0x1bb3bb4d, 0x186bea92, 0x4800918a, 0x10dc40b9, 0xfa3366db, 0x248b3457, 0x15fdf32f,
        0xef932aeb, 0x2e6f7e1e, 0xd9db1996, 0x00c701f6, 0x2c88cf9e, 0x50c0ab38, 0xfd5e9bfa, 0x1644bc44,
        0xc7d6d27a, 0xf753cb6c, 0xdfb46ee2, 0x568e4507, 0x2d63ab8f, 0xc1ad492f, 0xc7057184, 0x70ac3263,
        0x6139a540, 0x92bfe0eb, 0xdca3e211, 0xaec66727, 0x18e49103, 0x1c9ae763, 0x10eb32f5, 0x2a07d629,
        0x71a3a176, 0x75456c30, 0x88d48287, 0xa0cd4122, 0xe9935a54, 0x22dd851f, 0x25c2336f, 0x1a9d983b,
        0x1fbacd7e, 0xf8365d85, 0xac351e95, 0x5d72a1c7, 0xc4f34f59, 0xebf96cc1, 0x1a700615, 0x34fc3faf,
        0x5e05c040, 0x7179a0b0, 0x3137ba1b, 0xc6212258, 0x9bf74a13, 0xccb43add, 0x0728605d, 0xdf0e419a,
        0xc23400b3, 0xe4190303, 0x0a879883, 0xe4fff553, 0x2fe3c3cb, 0xa707bb1c, 0x0b652ce4, 0xd3236310,
        0xdd5a5515, 0xef7638f5, 0x63f71097, 0xa3a51805, 0x9312b2a4, 0x158b74ee, 0x0aacb30c, 0x3c6dad3c,
        0xca1b6a36, 0x67b85ef5, 0xfbbf7f91, 0xaddcc1b9, 0x1d547ada, 0x7a4010e5, 0xb59ca257, 0xe5e60902,
        0x125d2ac5, 0x73764ded, 0x83a415a6, 0x9e63d942, 0x617cc0dd, 0x732e16e4, 0x156907d4, 0x10d293c9,
        0x304374e8, 0xd9eba4e8, 0xb48946cb, 0x183cc1d0, 0x7956ac5c, 0x6597af28, 0x90336bfb, 0x39766a53,
        0x925dddb4, 0xfe1dd4d2, 0x41829312, 0x129eff46, 0x5be42ca2, 0xd65edf9d, 0x1ada6cac, 0x2effad79,
        0x5e6860c3, 0x8dfc2002, 0xd78fc2c9, 0x3f23c0ed, 0x5f212b11, 0x9c0e8c71, 0xd44e7932, 0x664ce025,
        0x9c0e5ba8, 0x20cb2770, 0xe367f99b, 0x9a136c99, 0x6efe6560, 0x0e770a91, 0x6d29d7f5, 0x2eb6ca5b,
        0xb870ada0, 0x18418680, 0x4b94bf15, 0xcd5cd9a3, 0x49edcef0, 0x94417671, 0x9bb5380d, 0x41670e46,
        0x7557c0bd, 0x6b05d0a5, 0x4badd3a4, 0x54bbab72, 0x34ea5085, 0xe7c33532, 0x0d496c88, 0x4773ca3e,
        0x6f95b383, 0x6a866bb6, 0x807af334, 0xc27babe3, 0x2dd69c38, 0x7febb269, 0x26448f6f, 0xef6fb117,
        0x01a85a83, 0x6d1defef, 0x9177c78c, 0x0620e067, 0x05d1bbd6, 0x70bd3dc9, 0x96df9cb9, 0x611dc54c,
        0x89e1dac5, 0x896660bd, 0xf0e59c35, 0x4d6d751e, 0x567c7b39, 0x5fcd33a5, 0x75d64604, 0x61de0a58,
        0x7961e658, 0x3c6db043, 0xd1a17bf5, 0x17878923, 0x422e2b83, 0x0e982b46, 0x4c0c77e3, 0x625ab918,
        0x7c450a7e, 0x91f4b7c4, 0x71f2ccce, 0x49799da5, 0x69b64322, 0x912c08c0, 0xe361b62c, 0xe2a1301e,
        0x32edb753, 0x714b498a, 0x91fc62c4, 0xa834453e, 0x394fcc5d, 0x5746cd13, 0xe14eedca, 0x7e038145,
        0x6f9d3c5f, 0xc5726f88, 0xf76a9a70, 0x5d656c57, 0xdb28be76, 0x28602d67, 0x25ef3e19, 0x2b266092,
        0xa12feacc, 0x6d660143, 0xfd40dd16, 0x9ff5c8d2, 0xd094076a, 0x1a6b6df8, 0x68e269b6, 0xee9adea3,
        0x8e4cfeb9, 0x07b1a509, 0x4dca94df, 0xf6d521b4, 0x89ae4764, 0x663d12ff, 0x818069df, 0x8b69ad44,
        0x2672fe38, 0xbb831ef6, 0x65d466e3, 0x83b0c565, 0x4e0383ea, 0x82b0c735, 0x48623756, 0x8d61ed59,
        0x2468b57b, 0x8c513096, 0x011f26e9, 0xdd6394d0, 0x97467705, 0xe5f8fc52, 0x5663cc6b, 0x59d85c3f,
        0x066b88bf, 0x367783d2, 0x19a39a2c, 0x14c5809d, 0x6959fbcc, 0xbb92e531, 0x03ebb85c, 0x6cc9deba,
    };
    private static final int[] S1 = {
        0xe894d060, 0x0e3001b6, 0x12a3038f, 0x10439d06, 0x9e3711e9, 0x711c4469, 0xbfcd556c, 0x9af47d13,
        0xb9fcfb53, 0xbe514019, 0xe95bcdb0, 0x2e3195b6, 0xd4f77f18, 0x752971cf, 0x45e2def4, 0xfc9c8420,
        0x2f018f8c, 0xae3a906d, 0xc601d49a, 0x4d9eae1b, 0x98170200, 0x81953c95, 0x74a88eb7, 0x6d08423e,
        0xac8535ea, 0xa82f2aa7, 0xf08ee3bf, 0xf1cceef5, 0x97a98a8e, 0xa9adc06d, 0xb02839fe, 0x33f12f5c,
        0x8b6f8b29, 0xabdb5315, 0xef158525, 0x97bc5853, 0x4daba4c7, 0x91c9c31e, 0x74c7530a, 0xbd8a0bd0,
        0x7d6b0c3a, 0x7cd00359, 0xc6f90f72, 0xf786a46e, 0x5f1181c2, 0x3f7b7600, 0xba3ccabf, 0x0682663b,
        0x82a1719f, 0xf0eb3829, 0x2fde51c8, 0x6ce2df0d, 0xb144ad3e, 0x29b3032b, 0x7b43173c, 0x4528d97f,
        0xa6df84ce, 0x97f14a24, 0xe924520d, 0x37df5599, 0xaad4b5c8, 0xbf651aea, 0x39cbe96c, 0x7bfab1df,
        0xfc12523a, 0x2b5f8daf, 0x40a070b2, 0xe58e3cd9, 0x88e848f6, 0x22265ff5, 0xc9f7e278, 0x3066a3a0,
        0xe77eaf8a, 0x23cdf151, 0x10670044, 0xd71685fb, 0x22258439, 0x04c6f650, 0x17e3525b, 0x46bf9e6b,
        0x730ab2a0, 0x027a9127, 0x8174e450, 0x46a35c6d, 0xd96fbb22, 0xf812b8c6, 0x815cc9cc, 0xb6d26b98,
        0x0b2f6b3e, 0x39ba7b51, 0xe75f5a00, 0x0b32db09, 0xedeb894f, 0x60332c76, 0x4af31da5, 0x5477de96,
        0x6db7c801, 0x34f733d3, 0x1e48655a, 0x9d3db2d4, 0x5c9e3e0f, 0x3d5b75b8, 0xd9ac7a0f, 0xe1bf5a45,
        0x988874ff, 0x98192e44, 0x2551dadc, 0xba9391b4, 0x484cb94c, 0x0766af68, 0xde2ec4f2, 0x03824d45,
        0xfe242671, 0xaeb27dd5, 0x871ec2c1, 0x88d7b136, 0xebff8881, 0xed927e46, 0x0a30891e, 0x5c8ffc64,
        0xdf9bb19d, 0x899d9c41, 0x806665c5, 0x80997fde, 0x544965bc, 0xb53ea8f3, 0x74c53dba, 0x6797c884,
        0xada3ff05, 0x0a595ebc, 0x93698f1c, 0x7d3efee8, 0xd83ebe01, 0xd0182e33, 0x67d7f40c, 0x9a8744b1,
        0xab96f302, 0x093f8974, 0x3d5d5cb8, 0x0471f80a, 0xd32a3709, 0x82a1b4dc, 0xdaa4c9c5, 0x55f6d44b,
        0x30201584, 0xb6b66d1a, 0xa6809db7, 0x24f38688, 0xcd3da5f4, 0x18d0e534, 0xcf18b01d, 0x94be3d33,
        0x14ce450b, 0xbe35415f, 0x61ff50a9, 0xd7846ca8, 0xab9a6938, 0x164516a0, 0x12a04115, 0x124a8ee7,
        0x20ecf88f, 0x9d9cbeda, 0xdb756d6a, 0x432590e6, 0x93d99899, 0xc5b80ac2, 0xcdf0f7dc, 0x48860cd2,
        0xe67328e8, 0x0dbe1024, 0xebb47201, 0x794f2ab3, 0xbdabdc50, 0x06f4ebcd, 0x61a721eb, 0x767fd04c,
        0x6b83f5d1, 0x0e76772b, 0xcb0e2df4, 0xa74724ea, 0xc8fc5f6f, 0xda0df1e7, 0x62b3ca6c, 0x9874de09,
        0x279c52ca, 0xef555244, 0xd3c2215a, 0xb2b4c49e, 0xc0f5c7c9, 0x09a20f1d, 0xc04f7e19, 0x05a3293d,
        0xc66c9926, 0xbc40a8da, 0x92fa5204, 0xc5785bcc, 0xb19a1a52, 0x8ffa123d, 0xc883dddd, 0xaca017bf,
        0x70cf064a, 0x31e07019, 0x5d011776, 0x31582e1f, 0xdacd07a2, 0xca5e98af, 0x4da41b23, 0xb1b33a85,
        0x12de3012, 0x41789180, 0xf1af23bb, 0x5163bea9, 0xafb8bfbf, 0x2357db6a, 0x5a6589bd, 0x3b225aab,
        0xc0bb6444, 0x8dad4818, 0xaf690ebc, 0x586ecf17, 0x7821f730, 0xf3fe9d8f, 0x7b3b4047, 0x1db5733f,
        0x428f8835, 0x8e4d0f4f, 0xee403136, 0x17a36bdb, 0x334739f6, 0x29dcdd17, 0x8491a619, 0xa0eb5874,
        0x7de4847b, 0xc278b2cb, 0x866bd275, 0x35db1871, 0xfff1ad5a, 0x540de0d1, 0x8fad7b85, 0x2e5ab073,
        0x2afd815c, 0x2455d1ed, 0xd536b84c, 0xf7ed5fa5, 0xeda02b4b, 0x811e8f3f, 0x150ee37a, 0x8df7e6ac,
        0x5b7b6daf, 0x54834935, 0x2b62655d, 0x52c30873, 0x5d0c4809, 0x330385a4, 0x5c97832f, 0x82ca9910,
    };
    private static final int[] S2 = {
        0xb67d47a1, 0x1d10f48a, 0x07afb6c7, 0x24309820, 0xff34fafd, 0x4727130e, 0xe2baf75d, 0xb0bf9e79,
        0x807bc508, 0x94b707e0, 0x4dfddefc, 0xd0eb643e, 0x962db528, 0x32af0368, 0x1553d6f5, 0xd825d616,
        0x79fd98c2, 0xc110eff4, 0xe53a227a, 0x91ef990d, 0x20d8d780, 0x05e38261, 0x643743ca, 0x8cab8cdb,
        0x7aea6253, 0x9e108dc2, 0x191f93d4, 0xbafb11e0, 0x1e4985a3, 0xc49c520f, 0x7d2ff962, 0x33de7840,
        0x127fb328, 0x20f0a4d1, 0x3b3ff93b, 0x414bab14, 0xaf109899, 0x7ace0283, 0x072ebbad, 0x1c358d32,
        0xeb519b6e, 0x1874afec, 0x432c99bc, 0x8c8cb09e, 0x52a89233, 0xd6dd8e20, 0x1656e05f, 0x8a9f2c97,
        0x1f001c6c, 0x5685fb5d, 0x1f681d35, 0xb756a665, 0x4c9f042c, 0xe07e209d, 0x3147f638, 0xd6bbbbdd,
        0x4a55a36c, 0x82bc9726, 0xd0e44f20, 0x23b4bee6, 0x7e097e30, 0xa74eb421, 0x7df552aa, 0x628f0751,
        0x82c8759f, 0x73109efd, 0x65834d28, 0x4d300bb7, 0xe83b6368, 0x2150cb4e, 0x1c6ac7fc, 0x057298b8,
        0x04ae8c3d, 0x224f8a24, 0x86c3e032, 0xfce605da, 0x754ded0e, 0x4abb0f92, 0x0f1d941e, 0x24284ce8,
        0xfdf14a54, 0x1b354491, 0xe8f4baf8, 0xbc38601d, 0xb77107cc, 0x65e2f365, 0x660fa4d7, 0x16eff231,
        0xb037c3c3, 0x1ecc05ea, 0x7077ada9, 0xed11499f, 0xf84bd177, 0xc9989226, 0x120d8b43, 0x2fdcedac,
        0xc04f2dc1, 0x670c0390, 0xbfd8f2e1, 0x3a4e6df6, 0x803b9ac2, 0x780d3de6, 0x81924404, 0xfb49446a,
        0x447ff375, 0x23cb97ac, 0x2a7102a9, 0xe4e3b0a3, 0x3496c47f, 0x86153809, 0x6606da3a, 0xca3109fb,
        0xa407f950, 0x4cfef1ad, 0xbe8d1881, 0x1f9575ea, 0xb88ebc72, 0xe181eb8d, 0x3cb5a944, 0x0ff55849,
        0x89b427be, 0x3702625d, 0xf2dc60f9, 0xa62c7269, 0x1354c62f, 0x7ac72d08, 0xce72cd77, 0x89f3387e,
        0xa308a6e0, 0xb587a717, 0x1252bd65, 0xdb9d4ad5, 0x25e5674b, 0x6f9454a6, 0x4d394f3f, 0x8576913f,
        0x32f4fe23, 0x9772b6fb, 0x9f7218ba, 0x0efb2779, 0xcb672dde, 0x0eb290bc, 0xcba87530, 0x24b37443,
        0xdfae02f4, 0x844c4333, 0x5a48e86b, 0x16876a3e, 0xdd7789b6, 0x0af60b65, 0x169864cf, 0x1350451f,
        0xfeb53363, 0x0cc2aa6e, 0xe645e688, 0x85188d7e, 0x3cfd6cda, 0x60737f73, 0x4515790d, 0x4f94dafe,
        0x196c2510, 0xaaf5f503, 0x2f0aa525, 0x6f392640, 0x24552f5f, 0x8c2e6fab, 0xafb81266, 0x1bef6621,
        0x2f52e969, 0xeeaaa604, 0x0239941b, 0xb5032195, 0x620deed5, 0xdb46ae75, 0x4f74258e, 0xad712bc0,
        0x190643f7, 0x2d6e7e17, 0x9834e0f5, 0x93e47152, 0x0db4b3e5, 0x6cbf4b21, 0x78e55f82, 0xaabe52eb,
        0x4e7c0735, 0x0c745e35, 0x1061c113, 0xc10a8a5c, 0x83700c07, 0xa54bc3e6, 0x526ceeef, 0x89c06413,
        0x6d38b4c7, 0x4673fd86, 0x03d2c646, 0x12430bb9, 0x370e9e8d, 0xbd0992d7, 0x7de151bf, 0xd451dcd8,
        0x698bd211, 0x8c6bc442, 0x4b47ec56, 0xe450b838, 0x802b65b4, 0x616c27f0, 0xd085a17f, 0x1bc2b89a,
        0x99f0ec22, 0x422a8789, 0x43ceb066, 0x4b03970f, 0xadc471a3, 0x66b9d4e0, 0xd61953e7, 0xbce5ec01,
        0x93e95855, 0xeb84e289, 0x4e77f6cd, 0x4840f5a4, 0x6ec8fc5e, 0x47e0b7c2, 0x71cc5f5b, 0x94013da6,
        0x4d14d4b5, 0xed712c8e, 0x73563060, 0x4e4e4016, 0x340d317e, 0x2961ce52, 0x128f9bd7, 0xeabfde46,
        0x3e44ea72, 0x633b7eac, 0xebf8210e, 0x21243555, 0xaac390e1, 0x5b942763, 0x38dd803b, 0x4d98a654,
        0x2cbace66, 0x468bbbe4, 0xb2b74914, 0x214755c4, 0x0d47e41d, 0x6dcbb92f, 0x02d265ee, 0x13ea277f,
        0x8ee86bfd, 0x6a6dc9b7, 0x9b29be82, 0xf8c62c9f, 0x30573e12, 0x4bd8d523, 0x78ee8376, 0x8bb366f7,
    };
    private static final int[] S3 = {
        0x5e58d3ec, 0x0cb67fea, 0x95f9218e, 0xe6efef4a, 0x2085b467, 0x8138c8e9, 0x39b5867e, 0x73a1ab58,
        0xb5f36e69, 0x15b8ceae, 0x7ebe805d, 0x434d22e1, 0xaa85350e, 0x09838577, 0xdc3e51c7, 0x60a4f0b6,
        0x2f2b9695, 0x2410fe09, 0xbdef5a30, 0x8a84854b, 0x180d6e09, 0x4e13528a, 0xfd4b5955, 0x1238fb27,
        0xe4ca81c6, 0xd83931ad, 0xadc882f7, 0x153542ea, 0xec742421, 0x771a9ef8, 0xea7f7aed, 0x6af3ad1b,
        0x12931269, 0x092cc242, 0x76958ddd, 0x5714d9ca, 0xff1268cd, 0x0941cb63, 0xd1dab4b5, 0xb7441523,
        0x77d73428, 0x1d2a926a, 0x9022eecb, 0xfe251631, 0x427df0c3, 0x4846bf96, 0x188ef5e3, 0x58fa1562,
        0xee5fc55c, 0xc6c08061, 0xcce689da, 0x442c417a, 0x761bd1a3, 0xe97c2bbc, 0x2ae0a174, 0x93b7b1c3,
        0xf04436d7, 0x726f2543, 0xe6a547e8, 0xbc0331ae, 0x1861842e, 0x62a8130a, 0x8421b9a7, 0xc8da8434,
        0x0c59bedc, 0xf3aa69e8, 0xa9beadae, 0x8f6261f9, 0x03628d3e, 0x052337c3, 0x6f82c479, 0xbbe190b9,
        0x27476c24, 0xca13df8f, 0x8833a361, 0xbd6117ba, 0x5c4d0769, 0xa44efc75, 0x89e175c4, 0x41492e66,
        0x26514f9f, 0x77d608f2, 0xf786c561, 0x2d91f3c9, 0x3f034599, 0x2e625d2f, 0x530a8763, 0x584f04db,
        0xa4c8f522, 0xca1e8139, 0xebf07ca4, 0x9ab3ca84, 0x76fc1d3b, 0x74148eea, 0x7085fc72, 0xf123f3d9,
        0x622b41c2, 0x6e521aeb, 0xe9776e1e, 0x12f0e2db, 0xc27cc3d6, 0xe5f00508, 0xb63156cd, 0xe82b6b8f,
        0xbc9cd5cb, 0x27a81e7c, 0xa2d7b6a2, 0xbb5325c6, 0xeb6210c1, 0xa62710c1, 0xbffbe751, 0x6d3a3f50,
        0x64b86d3e, 0x7b9ebbc6, 0xdda96f08, 0x7937ec94, 0xfdb5036c, 0x1f98fac3, 0x81227234, 0xad91c7c0,
        0x80557a09, 0xa7c5ed0e, 0x61103cb0, 0xd47fc1b3, 0xa45c8da7, 0xf61ab4d9, 0xd63121f4, 0x489d2038,
        0x6e7d15e5, 0x0e75a3a1, 0x13a31a0d, 0x677ce7d4, 0x640bc6d0, 0x94ab0c9d, 0xcebe83f7, 0xcdec7339,
        0x9485f9cf, 0x7991ba42, 0x23d99a12, 0xa1a0b21d, 0x6d4ee6fe, 0x18d483ee, 0x0dafe7f6, 0xb00f271f,
        0x56487268, 0x0b06e8a3, 0x96c7337a, 0x4ffd73bb, 0x384d7ff5, 0x5af2ae93, 0x7598bd3a, 0xa5f3f903,
        0x2dec6d4d, 0x01438e2e, 0x68069d82, 0x1785a7a1, 0x269a5801, 0xfb5b81d6, 0xbe0c0d41, 0xd576193b,
        0x1872b315, 0x68147642, 0xc3f7325c, 0xd20a6539, 0xcc915c6d, 0x19563a44, 0xfea26756, 0xc5ade008,
        0xd833f7f6, 0x3a831760, 0xd4cf8509, 0xdad122d3, 0x0245b51e, 0x41c2b3f9, 0xc91263f2, 0xb656c339,
        0xf8d11c17, 0x677bd454, 0x2dece7d9, 0x7e5978df, 0xdabda403, 0x3a990b04, 0x7ed21fb6, 0x51e1c7fb,
        0x7a571f4c, 0x2cd3471f, 0x564d5880, 0xb2094bf6, 0xf136b07d, 0x344292b7, 0xdfd10775, 0x750abe62,
        0x1223aebd, 0xb4de549a, 0x062d6969, 0xeb8a497d, 0x5d3f61f8, 0xd6cc1c50, 0x333705ea, 0xb3ce9a7e,
        0x8ff83b7a, 0xa50fb659, 0x830605b5, 0x2602713a, 0x17feb477, 0x86bb330e, 0x492f45ea, 0x5097c6b7,
        0x250d5ce5, 0x4777105c, 0x81c4c176, 0xfa3c9cfe, 0x06fd393b, 0x9a66219b, 0x013dfc64, 0xae70fb28,
        0x4279f18d, 0xb372ebd0, 0xd0524acb, 0xd3c80356, 0x2800c41d, 0xcd132f3e, 0xa809c96b, 0x255fba93,
        0xd29d9f68, 0x5393c6e7, 0xf8f5fa0d, 0xb7995b44, 0x2d12623a, 0x789b677e, 0x540a2b5f, 0xc5b57664,
        0x13418c3c, 0x9533bf9d, 0xa5f5b7d9, 0xda908ddb, 0x59c3cd1c, 0x472ddc57, 0x899612bf, 0xc0772d06,
        0x6faf5770, 0xbcc706d3, 0xc91ab8e7, 0x67a98f50, 0xb3ffe169, 0xe69ee50f, 0x1d593371, 0x489229d1,
        0xc08755e9, 0x1fbdc739, 0xacc17f01, 0x166630fe, 0x5c551fa1, 0xae88a5ef, 0x3d595979, 0x7352a7b9,
    };
    private static int F(int x){
        int b3=(x>>>24)&0xff,b2=(x>>>16)&0xff,b1=(x>>>8)&0xff,b0=x&0xff;
        return ((S0[b3]+S1[b2]) ^ (S2[b1]+S3[b0]));
    }
    private static int le(byte[] b,int o){ return (b[o]&0xff)|((b[o+1]&0xff)<<8)|((b[o+2]&0xff)<<16)|((b[o+3]&0xff)<<24); }
    private static void pe(byte[] b,int o,int v){ b[o]=(byte)v; b[o+1]=(byte)(v>>>8); b[o+2]=(byte)(v>>>16); b[o+3]=(byte)(v>>>24); }

    /* ---- header: modified Blowfish + whiten ---- */
    private static void whiten(byte[] h){ for(int i=1;i<8;i++) h[i]^=(~(h[i-1]<<1))&0xff; }
    private static void unwhiten(byte[] h){ for(int i=7;i>=1;i--) h[i]^=(~(h[i-1]<<1))&0xff; }
    private static void bfEnc(byte[] h){
        int L=le(h,0),R=le(h,4);
        for(int i=0;i<16;i++){ L^=P[i]; R^=F(L); int t=L;L=R;R=t; }
        int t=L;L=R;R=t; R^=P[16]; L^=P[17]; pe(h,0,L); pe(h,4,R);
    }
    private static void bfDec(byte[] h){
        int L=le(h,0),R=le(h,4);
        for(int i=17;i>1;i--){ L^=P[i]; R^=F(L); int t=L;L=R;R=t; }
        int t=L;L=R;R=t; R^=P[1]; L^=P[0]; pe(h,0,L); pe(h,4,R);
    }
    /** decode an 8-byte header in place: wire -> raw plaintext */
    public static byte[] decodeHeader(byte[] wire8){ byte[] h=wire8.clone(); bfDec(h); unwhiten(h); return h; }
    /** encode an 8-byte header in place: raw plaintext -> wire */
    public static byte[] encodeHeader(byte[] raw8){ byte[] h=raw8.clone(); whiten(h); bfEnc(h); return h; }

    public static boolean checksumOk(byte[] raw8){
        int c=0; for(int i=0;i<7;i++) c+=raw8[i]&0xff; return (c&0xff)==(raw8[7]&0xff);
    }
    public static int msgId(byte[] raw8){ return (raw8[1]&0xff)|((raw8[2]&0xff)<<8); }
    public static int seq(byte[] raw8){ return (raw8[3]&0xff)|((raw8[4]&0xff)<<8); }
    public static int payloadBlocks(byte[] raw8){ return raw8[6]&0xff; }

    /* ---- payload: AES-128-ECB with a PER-FRAME key derived from the header ----
     * BREAKTHROUGH (live-verified): the payload AES key is NOT a fixed global key. It is
     * derived per-frame from the 8 PLAINTEXT header bytes H as:
     *       key = whiten(H) || H            (16 bytes = AES-128 key)
     * where whiten() is the SAME chained whitening used on the header cipher.
     * The old "KeyB" (e1397ef5...) was simply one frame's per-frame key that got extracted
     * live and mistaken for a global constant - which is why CBC/CTR/whitening models all
     * failed (there is no keystream; it's plain ECB with a key that changes every frame),
     * and why frames whose header differed from that one decoded to garbage on the client.
     * The client decrypts each frame's payload with whiten(H)||H of THAT frame's header, so
     * the server must encrypt with the same per-frame key.
     * PROOF: AES_dec(whiten(H)||H, our_old_wire) reproduced the client's exact post-decode
     * garbage byte-for-byte; and the live AES key schedule held whiten(H)||H for a keepalive
     * frame, byte-perfect. */
    private static byte[] aes(int mode, byte[] key, byte[] data){
        try { Cipher c=Cipher.getInstance("AES/ECB/NoPadding");
              c.init(mode,new SecretKeySpec(key,"AES")); return c.doFinal(data); }
        catch(Exception e){ System.out.println("[BrokerCrypto] AES err: "+e); return data.clone(); }
    }
    /** Per-frame AES-128 key = whiten(H) || H, from the 8 plaintext header bytes. */
    private static byte[] payloadKey(byte[] header8){
        byte[] w = header8.clone();
        whiten(w);                                  // same chained whitening as the header cipher
        byte[] key = new byte[16];
        System.arraycopy(w,        0, key, 0, 8);   // [0:8]  = whiten(H)
        System.arraycopy(header8,  0, key, 8, 8);   // [8:16] = H
        return key;
    }
    /** Encrypt the payload with the per-frame key derived from this frame's plaintext header. */
    public static byte[] encodePayload(byte[] p, byte[] header8){
        if(p.length==0) return p;
        // Pad to a 16-byte multiple (AES-ECB block size). blockCount in the header covers the padded length.
        int padded=((p.length+15)/16)*16;
        if(padded!=p.length){ byte[] q=new byte[padded]; System.arraycopy(p,0,q,0,p.length); p=q; }
        return aes(Cipher.ENCRYPT_MODE, payloadKey(header8), p);
    }
    public static byte[] decodePayload(byte[] p, byte[] header8){
        return (p.length==0)?p:aes(Cipher.DECRYPT_MODE, payloadKey(header8), p);
    }

    /* ---- whole frame ---- */
    /** plaintext frame (8-byte header + payload) -> wire bytes */
    public static byte[] encodeFrame(byte[] plain){
        // HEADER NORMALIZATION (lets b6ooy's STOCK acks work unchanged):
        // Stock getData() builds the header in b6ooy's format ([5:6]=pSize). The REAL client
        // wants [5]=random, [6]=blockCount. We keep [0]=random,[1:2]=msgId,[3:4]=seq from
        // getData, then OVERWRITE [5]=random, [6]=blockCount (from payload length), and
        // recompute [7]=checksum. So any stock ack's header is corrected here, in one place.
        byte[] raw = java.util.Arrays.copyOfRange(plain, 0, 8);
        int payLen = plain.length - 8;
        int blocks = (payLen + 15) / 16;          // 16-byte AES blocks
        raw[5] = (byte) (new java.util.Random().nextInt() & 0xff);  // [5] = random
        raw[6] = (byte) (blocks & 0xff);          // [6] = block count (our format)
        int cs = 0; for (int i = 0; i < 7; i++) cs += raw[i] & 0xff;
        raw[7] = (byte) (cs & 0xff);              // [7] = checksum over [0..6]

        byte[] hdr=encodeHeader(raw);
        // PER-FRAME PAYLOAD KEY: the payload AES key is whiten(raw)||raw, derived from THIS frame's
        // finalized plaintext header (raw, with [5]=random,[6]=blockCount,[7]=checksum already set).
        // Pass raw so encodePayload keys the AES the same way the client does on decode.
        byte[] pay=encodePayload(java.util.Arrays.copyOfRange(plain,8,plain.length), raw);
        // FRAME LENGTH FIX (live-confirmed via ServerDir.dll recv path + piVar4 buffer):
        // The client frames EVERY broker message as EXACTLY 8 header + blockCount*16, computed
        // from blockCount (header[6]) ALONE. It does NOT expect a separate status byte on the wire.
        // The status/result byte is the FIRST PLAINTEXT byte of the payload (payload[0]), encrypted
        // INTO block 0 - which getData already supplies (its leading 0x00 byte decrypts to payload[0]
        // = status). Our previous code ALSO inserted a STANDALONE 0x00 at wire[8], making every frame
        // 1 byte too long (8 + 1 + blockCount*16). The first frame (chainlist) tolerated the orphan,
        // but the next frame (worldlist) inherited it -> its header read 1 byte early -> mis-framed ->
        // never enqueued -> never reached the parser (TCP ACKed all 41B, but parse buffer never saw it).
        // FIX: emit exactly 8 header + blockCount*16. The status stays as payload[0] inside block 0.
        byte[] out=new byte[8 + pay.length];
        System.arraycopy(hdr,0,out,0,8);          // [0:8] header
        System.arraycopy(pay,0,out,8,pay.length); // [8:] encrypted payload (payload[0] = status, inside block 0)
        return out;
    }
    /** wire bytes (8-byte header + payload) -> plaintext frame */
    public static byte[] decodeFrame(byte[] wire){
        byte[] hdr=decodeHeader(java.util.Arrays.copyOfRange(wire,0,8));
        // Per-frame payload key = whiten(hdr)||hdr, from the DECODED plaintext header.
        byte[] pay=decodePayload(java.util.Arrays.copyOfRange(wire,8,wire.length), hdr);
        byte[] out=new byte[hdr.length+pay.length];
        System.arraycopy(hdr,0,out,0,8); System.arraycopy(pay,0,out,8,pay.length); return out;
    }

    private static String hx(byte[] b){ StringBuilder s=new StringBuilder(); for(byte x:b) s.append(String.format("%02x",x&0xff)); return s.toString(); }
    private static byte[] uh(String s){ byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16); return b; }
    public static void main(String[] a){
        byte[] raw=decodeHeader(uh("c9729f69ced9bb70"));
        System.out.println("decodeHeader  = "+hx(raw)+"  msgId=0x"+Integer.toHexString(msgId(raw))+"  cksOk="+checksumOk(raw));
        System.out.println("encode(decode)= "+hx(encodeHeader(raw))+"  (expect c9729f69ced9bb70)");
    }
}
