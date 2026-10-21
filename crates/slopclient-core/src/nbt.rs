//! Minimal big-endian NBT reader for the few files the launcher looks into
//! (`level.dat`, `servers.dat`).

pub(crate) struct Nbt<'a> {
    pub(crate) data: &'a [u8],
    pub(crate) pos: usize,
}

pub(crate) type Visit<'v> = dyn FnMut(&mut Nbt, u8, &str, &str) -> Option<bool> + 'v;

impl Nbt<'_> {
    pub(crate) fn take(&mut self, n: usize) -> Option<&[u8]> {
        let end = self.pos.checked_add(n)?;
        let slice = self.data.get(self.pos..end)?;
        self.pos = end;
        Some(slice)
    }
    pub(crate) fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }
    pub(crate) fn u16(&mut self) -> Option<u16> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?))
    }
    pub(crate) fn i32(&mut self) -> Option<i32> {
        Some(i32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }
    pub(crate) fn i64(&mut self) -> Option<i64> {
        Some(i64::from_be_bytes(self.take(8)?.try_into().ok()?))
    }
    pub(crate) fn string(&mut self) -> Option<String> {
        let len = self.u16()? as usize;
        // Modified UTF-8; plain UTF-8 decoding is right for everything but rare characters.
        Some(String::from_utf8_lossy(self.take(len)?).into_owned())
    }

    /// Walks a compound's entries. `visit` reads the value itself and returns true,
    /// or returns false to have it skipped. Nested compounds are walked with their path.
    pub(crate) fn compound(&mut self, visit: &mut Visit, path: &str) -> Option<()> {
        loop {
            let tag = self.u8()?;
            if tag == 0 {
                return Some(());
            }
            let name = self.string()?;
            if visit(self, tag, &name, path)? {
                continue;
            }
            if tag == 10 {
                let inner = if path.is_empty() { name } else { format!("{path}/{name}") };
                self.compound(visit, &inner)?;
            } else {
                self.skip(tag)?;
            }
        }
    }

    pub(crate) fn skip(&mut self, tag: u8) -> Option<()> {
        match tag {
            1 => drop(self.take(1)?),
            2 => drop(self.take(2)?),
            3 | 5 => drop(self.take(4)?),
            4 | 6 => drop(self.take(8)?),
            7 => {
                let n = self.i32()?.max(0) as usize;
                self.take(n)?;
            }
            8 => drop(self.string()?),
            9 => {
                let inner = self.u8()?;
                let n = self.i32()?.max(0);
                for _ in 0..n {
                    self.skip(inner)?;
                }
            }
            10 => self.compound(&mut |_, _, _, _| Some(false), "\u{0}")?,
            11 => {
                let n = self.i32()?.max(0) as usize;
                self.take(n.checked_mul(4)?)?;
            }
            12 => {
                let n = self.i32()?.max(0) as usize;
                self.take(n.checked_mul(8)?)?;
            }
            _ => return None,
        }
        Some(())
    }
}
